package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.persistence.UserMemoryStore
import com.sebialex.agent.data.shell.Shell
import com.sebialex.agent.data.shell.ShellResult
import com.sebialex.agent.data.web.FetchedPage
import com.sebialex.agent.data.web.WebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.agent.AgentMode
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.permission.PermissionDecision
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The user_memory contract: the store, its tool handling, prompt injection and availability. */
class UserMemoryTest {

    private fun store() = UserMemoryStore(Files.createTempDirectory("umem").toFile())

    @Test fun emptyStoreRendersAnEmptyProfile() = runBlocking {
        assertEquals("", store().profile())
    }

    @Test fun rememberNormalizesAndCaps() = runBlocking {
        val s = store()
        val saved = s.remember("  likes   dark themes  ", " Likes ")
        assertEquals("likes dark themes", saved!!.text)
        assertEquals("Likes", saved.topic)
        val long = store().remember("x".repeat(500))
        assertEquals(400, long!!.text.length)
    }

    @Test fun blankFactsAreRejected() = runBlocking {
        assertNull(store().remember("   "))
        assertTrue(store().all().isEmpty())
    }

    @Test fun distinctIdsAndNewestFirst() = runBlocking {
        val s = store()
        val a = s.remember("first", at = 100)!!
        val b = s.remember("second", at = 200)!!
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(b.id, a.id), s.all().map { it.id })
    }

    @Test fun updateKeepsCreatedTouchesEdited() = runBlocking {
        val s = store()
        val m = s.remember("likes tea", at = 100)!!
        assertTrue(s.update(m.id, "likes coffee", "Drinks", at = 200))
        val after = s.all().single()
        assertEquals("likes coffee", after.text)
        assertEquals("Drinks", after.topic)
        assertEquals(100, after.createdAt)
        assertEquals(200, after.updatedAt)
        assertFalse(s.update("no-such-id", "x"))
    }

    @Test fun forgetAndClear() = runBlocking {
        val s = store()
        val m = s.remember("fact")!!
        assertTrue(s.forget(m.id))
        assertFalse(s.forget(m.id))
        s.remember("more")
        s.clear()
        assertTrue(s.all().isEmpty())
    }

    @Test fun fullStoreRefusesMore() = runBlocking {
        val s = store()
        repeat(100) { i -> s.remember("fact $i", at = i.toLong()) }
        assertNull(s.remember("one more"))
    }

    @Test fun persistenceSurvivesRestart() = runBlocking {
        val dir = Files.createTempDirectory("umem2").toFile()
        val m = UserMemoryStore(dir).remember("plays guitar", "Music")!!
        assertEquals(listOf(m.text), UserMemoryStore(dir).all().map { it.text })
    }

    @Test fun toolActionsWork() = runBlocking {
        val store = store()
        val tools = ToolExecutor(
            workspace = Workspace(Files.createTempDirectory("ws").toFile()),
            shell = unusedShell(),
            web = noWeb(),
            userMemory = store,
        )
        val saved = tools.execute(ToolCall.UserMemory("c1", "remember", text = "prefers short answers", topic = "Style"))
        assertTrue(saved.ok)
        assertTrue("remembered: prefers short answers (Style)" in saved.output)
        val id = store.all().single().id
        val listed = tools.execute(ToolCall.UserMemory("c2", "list"))
        assertTrue(listed.ok && "$id: prefers short answers (Style)" in listed.output)
        val updated = tools.execute(ToolCall.UserMemory("c3", "update", id = id, text = "prefers concise answers"))
        assertTrue(updated.ok)
        val missing = tools.execute(ToolCall.UserMemory("c4", "update", id = "gone", text = "x"))
        assertTrue(!missing.ok && "No memory with id gone" in missing.output)
        val forgotten = tools.execute(ToolCall.UserMemory("c5", "forget", id = id))
        assertTrue(forgotten.ok && store.all().isEmpty())
        assertTrue(!tools.execute(ToolCall.UserMemory("c6", "wipe")).ok)
        assertTrue("needs text" in tools.execute(ToolCall.UserMemory("c7", "remember")).output)
        val before = store.all().size
        tools.execute(ToolCall.UserMemory("c8", "remember", text = "spare"))
        assertEquals(before + 1, store.all().size)
    }

    @Test fun withoutTheStoreTheToolFailsCleanly() = runBlocking {
        val tools = ToolExecutor(
            workspace = Workspace(Files.createTempDirectory("ws2").toFile()),
            shell = unusedShell(),
            web = noWeb(),
        )
        val r = tools.execute(ToolCall.UserMemory("c9", "list"))
        assertTrue(!r.ok && "not available" in r.output)
    }

    @Test fun userMemoryChangesAreAllowedWithoutApproval() {
        val policy = PermissionPolicy()
        assertEquals(PermissionDecision.Allow, policy.evaluate(ToolCall.UserMemory("1", "remember", text = "likes ice cream")))
        assertEquals(PermissionDecision.Allow, policy.evaluate(ToolCall.UserMemory("2", "forget", id = "um-x")))
    }

    @Test fun toolSchemaOnlyWhenEnabled() {
        val off = OpenRouterBrain.toolsFor(memory = false, userMemory = false)
        val on = OpenRouterBrain.toolsFor(memory = false, userMemory = true)
        assertFalse(names(off).contains("user_memory"))
        assertTrue(names(on).contains("user_memory"))
    }

    @Test fun subagentContextHidesUserMemoryTool() {
        val ctx = BrainContext(
            task = "t", history = emptyList(), observations = emptyList(), workspaceFiles = emptyList(),
            mode = AgentMode.SUBAGENT, memoryTool = true, userMemoryTool = true,
        )
        assertTrue(names(OpenRouterBrain.toolsFor(ctx)).none { it == "user_memory" })
    }

    @Test fun profileSectionRidesAlongCustomSection() {
        val blank = BrainContext(task = "t", history = emptyList(), observations = emptyList(), workspaceFiles = emptyList())
        assertEquals("", blank.customSection())
        val withProfile = blank.copy(userProfile = "\n# What you know about the user\n- likes tea\n", customPrompt = "be brief")
        val section = withProfile.customSection()
        assertTrue("be brief" in section && "# What you know about the user" in section && "likes tea" in section)
    }

    @Test fun parseAcceptsActionAndArguments() {
        val parsed = OpenRouterBrain.parseToolCall("c1", "user_memory", """{"action":"Remember","text":"likes tea","topic":"Drinks"}""")
        assertTrue(parsed is ToolCall.UserMemory && parsed.action == "remember" && parsed.text == "likes tea" && parsed.topic == "Drinks")
        val bare = OpenRouterBrain.parseToolCall("c2", "user_memory", """{"action":"list"}""")
        assertTrue(bare is ToolCall.UserMemory && bare.action == "list" && bare.id == null)
        val noAction = OpenRouterBrain.parseToolCall("c3", "user_memory", """{"text":"x"}""")
        assertTrue(noAction is ToolCall.Invalid)
    }

    @Test fun wireNameMatches() {
        assertEquals("user_memory", OpenRouterBrain.toolName(ToolCall.UserMemory("1", "list")))
    }

    private fun systemOf(ctx: BrainContext): String {
        val messages = OpenRouterBrain(apiKey = "k", model = "m").messages(ctx)
        return ((messages[0] as JsonObject)["content"] as JsonPrimitive).content
    }

    @Test fun theLearningHintReachesTheSystemPromptOnlyWhenTheToolIsOn() {
        val off = BrainContext(task = "t", history = emptyList(), observations = emptyList(), workspaceFiles = emptyList())
        assertFalse("user_memory" in systemOf(off))
        val on = off.copy(userMemoryTool = true)
        val system = systemOf(on)
        assertTrue("user_memory" in system)
        // The hint must make the model save proactively, not "when it notices something".
        assertTrue("same turn" in system)
        assertTrue("without waiting to be asked" in system)
    }

    @Test fun savedProfileFactsReachTheSystemPrompt() {
        val ctx = BrainContext(
            task = "t", history = emptyList(), observations = emptyList(), workspaceFiles = emptyList(),
            userProfile = "\n# What you know about the user\n- Name is Alex (Identity)\n",
        )
        val system = systemOf(ctx)
        assertTrue("Name is Alex" in system)
        assertTrue("# What you know about the user" in system)
    }

    @Test fun subagentsGetTheProfileButNeitherHintNorTool() {
        val ctx = BrainContext(
            task = "t", history = emptyList(), observations = emptyList(), workspaceFiles = emptyList(),
            mode = AgentMode.SUBAGENT, userProfile = "\n- Name is Alex\n",
        )
        // Subagents run without the user_memory tool: no hint either, facts only.
        assertFalse("user_memory" in systemOf(ctx))
        assertTrue("Name is Alex" in systemOf(ctx))
    }

    private fun names(tools: JsonArray) = tools.flatMap { t ->
        val o = t as JsonObject
        listOf((o["name"] as? JsonPrimitive)?.content, (((o["function"] as? JsonObject)?.get("name")) as? JsonPrimitive)?.content)
            .filterNotNull()
    }

    private fun unusedShell() = object : Shell {
        override fun execute(command: String, cwd: String) = ShellResult("", 0, cwd)
    }

    private fun noWeb() = object : WebGateway {
        override suspend fun search(query: String, withImages: Boolean): List<SearchHit> = emptyList()
        override suspend fun fetch(url: String) = FetchedPage(url, "", "")
    }
}
