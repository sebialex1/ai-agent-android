package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.persistence.SessionStore
import com.sebialex.agent.data.shell.Shell
import com.sebialex.agent.data.shell.ShellResult
import com.sebialex.agent.data.web.FetchedPage
import com.sebialex.agent.data.web.WebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.AgentEvent
import com.sebialex.agent.domain.agent.AgentOrchestrator
import com.sebialex.agent.domain.agent.ApprovalGate
import com.sebialex.agent.domain.agent.ApprovalRequest
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.agent.TurnRecord
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The app's record of what earlier answers did ("[actions taken in this turn]") is context for
 * the model, never part of an answer: not in the replayed assistant messages, not in a
 * finished (streamed) answer, not in a saved and reopened conversation.
 */
class InternalRecordTest {

    private val header = TurnRecord.LEGACY_HEADER

    private val user = Turn("u1", Role.USER, "download the cat video", createdAt = 1)
    private val answer = Turn(
        "a1", Role.ASSISTANT, "Here's the video.",
        steps = listOf(
            Step("s1", StepKind.SHELL, "yt-dlp -o cat.mp4 https://x.y/v", "shell", "", status = StepStatus.DONE, startedAt = 2),
        ),
        artifacts = listOf(FileAttachment(path = "/downloads/cat.mp4", name = "cat.mp4", updatedAt = 3)),
        createdAt = 2,
    )

    private fun content(m: JsonObject) = (m["content"] as? JsonPrimitive)?.content.orEmpty()

    @Test
    fun replayedAnswersAreExactlyWhatTheUserSaw() {
        val brain = OpenRouterBrain(apiKey = "k", model = "m")
        val ctx = BrainContext("and now as mp3", listOf(user, answer), emptyList(), emptyList())
        val messages = brain.messages(ctx).map { it as JsonObject }
        val assistant = messages.filter { content(it).isNotEmpty() && (it["role"] as JsonPrimitive).content == "assistant" }
        assertEquals(listOf("Here's the video."), assistant.map(::content))
        // The record is still there for follow-ups, in the system prompt only, without the old header.
        val system = content(messages.first())
        assertTrue(system, "yt-dlp -o cat.mp4" in system && "/downloads/cat.mp4" in system)
        assertTrue("download the cat video" in system)
        messages.forEach { assertFalse(content(it), header in content(it)) }
    }

    @Test
    fun noRecordSectionWhenEarlierAnswersUsedNoTools() {
        val plain = answer.copy(steps = emptyList(), artifacts = emptyList())
        assertEquals("", TurnRecord.context(listOf(user, plain)))
    }

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

    private fun finish(text: String): String {
        val ws = Workspace(Files.createTempDirectory("record").toFile())
        val brain = object : AgentBrain {
            override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit) = BrainDecision.Answer(text)
        }
        val orchestrator = AgentOrchestrator(brain, ToolExecutor(ws, noShell, web = noWeb), PermissionPolicy(), ws, audit = {})
        return runBlocking { orchestrator.run("download it", emptyList(), allow).toList() }
            .filterIsInstance<AgentEvent.Finished>().single().text
    }

    @Test
    fun anEchoedRecordNeverReachesTheFinishedAnswer() {
        val echoed = "Here's the video.\n\n$header\n- shell: yt-dlp -o cat.mp4 https://x.y/v\n- attached: /downloads/cat.mp4"
        assertEquals("Here's the video.", finish(echoed))
    }

    @Test
    fun answersThatTalkAboutToolsAreKeptAsWritten() {
        // Not a sentence filter: an answer that mentions tools or has its own list stays intact.
        val text = "I used yt-dlp and ffmpeg for this.\n\nSteps you can repeat:\n- shell: run yt-dlp\n- convert with ffmpeg"
        assertEquals(text, finish(text))
    }

    @Test
    fun aReopenedConversationShowsAndReplaysNoRecord() {
        val dir = Files.createTempDirectory("sessions").toFile()
        // Saved by an older build: the model had copied the record into its answer.
        val leaked = answer.copy(text = "Here's the video.\n\n$header\n- shell: yt-dlp -o cat.mp4 https://x.y/v\n\nAnything else?")
        val clean = Turn("a2", Role.ASSISTANT, "I used yt-dlp:\n- fast\n- simple", createdAt = 5)
        SessionStore(dir).save(Session("c1", "Cat", listOf(user, leaked, clean), createdAt = 1))

        val reopened = SessionStore(dir).load("c1")!!
        assertEquals("Here's the video.\n\nAnything else?", reopened.turns[1].text)
        assertEquals(clean.text, reopened.turns[2].text)
        assertEquals(user.text, reopened.turns[0].text)
        // Steps (the visible trail) are untouched.
        assertEquals(leaked.steps, reopened.turns[1].steps)

        val messages = OpenRouterBrain(apiKey = "k", model = "m")
            .messages(BrainContext("next", reopened.turns, emptyList(), emptyList())).map { it as JsonObject }
        messages.drop(1).forEach { assertFalse(content(it), header in content(it)) }
    }

    @Test
    fun aFreshAnswerSurvivesSaveAndReload() {
        val dir = Files.createTempDirectory("sessions").toFile()
        val text = finish("Done.\n\n$header\n- shell: ls")
        SessionStore(dir).save(Session("c2", "t", listOf(user, answer.copy(text = text)), createdAt = 1))
        assertEquals("Done.", SessionStore(dir).load("c2")!!.turns[1].text)
    }
}
