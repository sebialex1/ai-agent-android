package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.shell.Shell
import com.sebialex.agent.data.shell.ShellResult
import com.sebialex.agent.data.web.FetchedPage
import com.sebialex.agent.data.web.WebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.AgentEvent
import com.sebialex.agent.domain.agent.AgentMode
import com.sebialex.agent.domain.agent.AgentOrchestrator
import com.sebialex.agent.domain.agent.ApprovalGate
import com.sebialex.agent.domain.agent.ApprovalRequest
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.agent.ChatTools
import com.sebialex.agent.domain.agent.SubagentControl
import com.sebialex.agent.domain.agent.SubagentRuns
import com.sebialex.agent.domain.agent.Subagents
import com.sebialex.agent.domain.agent.TurnRecord
import com.sebialex.agent.domain.agent.TurnStop
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.SubagentRun
import com.sebialex.agent.domain.model.SubagentStatus
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.Turn
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SubagentTest {

    private val noShell = object : Shell {
        override fun execute(command: String, cwd: String) = ShellResult("", 0, cwd)
    }
    private val noWeb = object : WebGateway {
        override suspend fun search(query: String): List<SearchHit> = emptyList()
        override suspend fun fetch(url: String) = FetchedPage(url, "", "")
    }
    private val allow = object : ApprovalGate {
        override suspend fun request(request: ApprovalRequest) = true
    }

    /** Main brain: first spawns [spawns], then answers with what the reports said. */
    private class Parent(private val spawns: List<ToolCall>) : AgentBrain {
        val seen = Collections.synchronizedList(mutableListOf<BrainContext>())
        override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
            seen += ctx
            if (ctx.observations.isEmpty()) return BrainDecision.Act(spawns)
            return BrainDecision.Answer("Combined: " + ctx.observations.joinToString(" | ") { it.result.output })
        }
    }

    private fun spawn(id: String, objective: String, model: String? = null) = ToolCall.SpawnSubagent(id, objective, model = model)

    private class Fixture(val ws: Workspace, val tools: ToolExecutor)

    private fun fixture(): Fixture {
        val ws = Workspace(Files.createTempDirectory("sub").toFile())
        return Fixture(ws, ToolExecutor(ws, noShell, web = noWeb))
    }

    private fun subagents(f: Fixture, models: List<String>, brainFor: (String) -> AgentBrain, picked: MutableList<String> = mutableListOf()) =
        object : Subagents {
            override fun models() = models
            override fun orchestrator(model: String): AgentOrchestrator {
                synchronized(picked) { picked += model }
                return AgentOrchestrator(brainFor(model), f.tools, PermissionPolicy(), f.ws, audit = {}, maxSteps = SubagentRuns.CHILD_MAX_STEPS)
            }
        }

    @Test
    fun subagentsRunInParallelAndReportToTheParent() = runBlocking {
        val f = fixture()
        val started = AtomicInteger()
        val both = CompletableDeferred<Unit>()
        // Each child waits until BOTH have started: run one after the other, this would time out.
        val child = { _: String ->
            object : AgentBrain {
                var calls = 0
                override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
                    assertEquals(AgentMode.SUBAGENT, ctx.mode)
                    if (calls++ == 0) {
                        if (started.incrementAndGet() == 2) both.complete(Unit)
                        withTimeout(5_000) { both.await() }
                        return BrainDecision.Act(listOf(ToolCall.ListFiles("c${ctx.task.length}", "/")))
                    }
                    return BrainDecision.Answer("found " + ctx.task.substringBefore('\n'))
                }
            }
        }
        val parent = Parent(listOf(spawn("s1", "alpha"), spawn("s2", "beta")))
        val orchestrator = AgentOrchestrator(parent, f.tools, PermissionPolicy(), f.ws, audit = {}, subagents = subagents(f, listOf("m1"), child))
        val events = withTimeout(10_000) { orchestrator.run("compare alpha and beta", emptyList(), allow).toList() }

        val answer = events.filterIsInstance<AgentEvent.Finished>().single().text
        assertTrue(answer, "found alpha" in answer && "found beta" in answer)
        val last = events.filterIsInstance<AgentEvent.SubagentUpdated>().groupBy { it.run.id }.values.map { it.last().run }
        assertEquals(2, last.size)
        assertTrue(last.all { it.status == SubagentStatus.DONE && it.steps.any { s -> s.tool == "list_files" } })
        // The children's steps never become steps of the parent turn.
        val parentSteps = events.filterIsInstance<AgentEvent.StepAdded>().map { it.step } + events.filterIsInstance<AgentEvent.StepUpdated>().map { it.step }
        assertTrue(parentSteps.none { it.tool == "list_files" })
        val childIds = last.flatMap { it.steps }.map { it.id }.toSet()
        assertTrue(parentSteps.none { it.id in childIds })
        // The main agent was offered spawn_subagent with the allowed models.
        assertEquals(listOf("m1"), parent.seen.first().subagentModels)
    }

    @Test
    fun theChildReadsTheMainConversationAndUsesTheChosenModel() = runBlocking {
        val f = fixture()
        val picked = mutableListOf<String>()
        val child = { _: String ->
            object : AgentBrain {
                override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision =
                    if (ctx.observations.isEmpty()) BrainDecision.Act(listOf(ToolCall.MainConversation("m", 4)))
                    else BrainDecision.Answer("context: " + ctx.observations.single().result.output)
            }
        }
        val history = listOf(
            Turn("u0", Role.USER, "my cat is called Miso", createdAt = 1),
            Turn("a0", Role.ASSISTANT, "Nice name.", createdAt = 2),
        )
        val parent = Parent(listOf(spawn("s1", "name the cat", model = "m2"), spawn("s2", "again", model = "nope")))
        val orchestrator = AgentOrchestrator(parent, f.tools, PermissionPolicy(), f.ws, audit = {}, subagents = subagents(f, listOf("m1", "m2"), child, picked))
        val answer = orchestrator.run("what is my cat called?", history, allow).toList().filterIsInstance<AgentEvent.Finished>().single().text
        assertTrue(answer, "Miso" in answer && "what is my cat called?" in answer)
        // An allowed model is used as asked; an unknown one falls back to the default (the first).
        assertEquals(setOf("m2", "m1"), picked.toSet())
    }

    @Test
    fun stoppingTheParentCancelsActiveChildren() = runBlocking {
        val f = fixture()
        val childStarted = CompletableDeferred<Unit>()
        val childCancelled = CompletableDeferred<Unit>()
        val child = { _: String ->
            object : AgentBrain {
                override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
                    childStarted.complete(Unit)
                    try { awaitCancellation() } finally { childCancelled.complete(Unit) }
                }
            }
        }
        val orchestrator = AgentOrchestrator(Parent(listOf(spawn("s1", "slow"))), f.tools, PermissionPolicy(), f.ws, audit = {}, subagents = subagents(f, listOf("m1"), child))
        val job = launch { orchestrator.run("go", emptyList(), allow).collect { } }
        withTimeout(5_000) { childStarted.await() }
        job.cancel()
        withTimeout(5_000) { childCancelled.await() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun stoppingOneSubagentLetsTheParentFinish() = runBlocking {
        val f = fixture()
        val child = { _: String ->
            object : AgentBrain {
                override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision = awaitCancellation()
            }
        }
        val control = SubagentControl()
        val orchestrator = AgentOrchestrator(Parent(listOf(spawn("s1", "slow"))), f.tools, PermissionPolicy(), f.ws, audit = {}, subagents = subagents(f, listOf("m1"), child))
        val events = mutableListOf<AgentEvent>()
        withTimeout(10_000) {
            orchestrator.run("go", emptyList(), allow, ChatTools.None, control).collect { e ->
                events += e
                if (e is AgentEvent.SubagentUpdated && e.run.status == SubagentStatus.RUNNING) control.stop(e.run.id)
            }
        }
        assertEquals(SubagentStatus.STOPPED, events.filterIsInstance<AgentEvent.SubagentUpdated>().last().run.status)
        assertTrue(events.filterIsInstance<AgentEvent.Finished>().single().text.contains("stopped"))
    }

    @Test
    fun theToolIsAdvertisedOnlyWhereItBelongs() {
        val main = OpenRouterBrain.toolsFor(BrainContext("t", emptyList(), emptyList(), emptyList(), subagentModels = listOf("a/b", "c/d"))).toString()
        assertTrue("\"spawn_subagent\"" in main && "\"a/b\"" in main && "\"c/d\"" in main)
        assertFalse("\"main_conversation\"" in main)
        val off = OpenRouterBrain.toolsFor(BrainContext("t", emptyList(), emptyList(), emptyList())).toString()
        assertFalse("\"spawn_subagent\"" in off)
        val child = OpenRouterBrain.toolsFor(BrainContext("t", emptyList(), emptyList(), emptyList(), mode = AgentMode.SUBAGENT, subagentModels = listOf("a/b"))).toString()
        assertTrue("\"main_conversation\"" in child)
        for (name in listOf("spawn_subagent", "ask_user", "todo", "set_plan", "attach_files", "preview_webapp")) assertFalse(name, "\"$name\"" in child)
        // Parsed like any other tool call, round-trips its arguments.
        val c = OpenRouterBrain.parseToolCall("1", "spawn_subagent", """{"objective":"find X","context":"use docs","model":"a/b"}""") as ToolCall.SpawnSubagent
        assertEquals("find X", c.objective)
        assertEquals("a/b", c.model)
        assertTrue(OpenRouterBrain.parseToolCall("2", "spawn_subagent", "{}") is ToolCall.Invalid)
        assertTrue("spawn_subagent" in OpenRouterBrain.subagentsPrompt(listOf("a/b")))
    }

    @Test
    fun aStoppedTurnClosesItsSubagentsAndTheyAreSavedApart() {
        val running = SubagentRun("r1", "look up X", "m1", steps = listOf(
            com.sebialex.agent.domain.model.Step("c1", com.sebialex.agent.domain.model.StepKind.SEARCH, "X", "web_search", "", startedAt = 1),
        ))
        val turn = Turn("a", Role.ASSISTANT, "", complete = false, createdAt = 1, subagents = listOf(running))
        val stopped = TurnStop.stopped(turn, now = 5)
        assertEquals(SubagentStatus.STOPPED, stopped.subagents.single().status)
        assertTrue(stopped.steps.isEmpty())
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val back = json.decodeFromString(Session.serializer(), json.encodeToString(Session.serializer(), Session("s", "t", listOf(stopped), createdAt = 0)))
        assertEquals(stopped, back.turns.single())
        // The main model later sees one line per subagent, not the subagent's transcript.
        assertTrue(TurnRecord.lines(stopped.copy(subagents = listOf(running.copy(status = SubagentStatus.DONE, result = "X is 4")))).single().contains("X is 4"))
    }

    @Test
    fun cardLines() {
        val r = SubagentRun("r", "o", "m", steps = listOf(
            com.sebialex.agent.domain.model.Step("1", com.sebialex.agent.domain.model.StepKind.SHELL, "yt-dlp -F url", "shell", "", status = com.sebialex.agent.domain.model.StepStatus.DONE, startedAt = 1),
        ))
        assertEquals("Working \u00b7 1 step completed", SubagentRuns.statusLine(listOf(r)))
        assertEquals("Ran yt-dlp -F url", SubagentRuns.detailLine(r))
        assertEquals("Done \u00b7 1 step", SubagentRuns.statusLine(listOf(r.copy(status = SubagentStatus.DONE))))
    }
}
