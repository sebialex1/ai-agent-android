package com.sebialex.agent

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
import com.sebialex.agent.domain.agent.AttachPolicy
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.GeneratedImage
import com.sebialex.agent.domain.tools.ImageGenerator
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AttachFilesTest {

    /** Plays back fixed decisions, one per model call. */
    private class ScriptedBrain(vararg decisions: BrainDecision) : AgentBrain {
        private val queue = ArrayDeque(decisions.toList())
        val seen = mutableListOf<BrainContext>()
        override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
            seen += ctx
            return queue.removeFirstOrNull() ?: BrainDecision.Answer("done")
        }
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
    private val png = ImageGenerator { _, _ -> GeneratedImage(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), "image/png") }

    private fun run(task: String, brain: AgentBrain, setup: (Workspace) -> Unit = {}): Pair<AgentEvent.Finished, Workspace> {
        val ws = Workspace(Files.createTempDirectory("attach").toFile())
        setup(ws)
        val tools = ToolExecutor(ws, noShell, web = noWeb, images = png)
        val orchestrator = AgentOrchestrator(brain, tools, PermissionPolicy(), ws, audit = {})
        val events = runBlocking { orchestrator.run(task, emptyList(), allow).toList() }
        return events.filterIsInstance<AgentEvent.Finished>().single() to ws
    }

    @Test
    fun toolCallJsonInTheAnswerTextAttachesNothing() {
        val leaked = "{\"action\":\"attach_files\",\"action_input\":\"{\\\"files\\\":[{\\\"name\\\":\\\"Water.png\\\",\\\"path\\\":\\\"/images/old.png\\\"}]}\"}\n\nHere is that shot for you."
        val (done, _) = run("make a picture of water", ScriptedBrain(BrainDecision.Answer(leaked))) { it.writeBytes("images/old.png", byteArrayOf(1, 2, 3)) }
        assertTrue(done.artifacts.isEmpty())
        assertTrue(done.attachments.isEmpty())
        // The text itself is left alone: legitimate JSON answers are not filtered.
        assertEquals(leaked, done.text)
    }

    @Test
    fun generateImageAloneDoesNotAttach() {
        val brain = ScriptedBrain(
            BrainDecision.Act(ToolCall.GenerateImage("c1", "water dripping in the desert")),
            BrainDecision.Answer("Here it is."),
        )
        val (done, ws) = run("make a picture of water dripping in the desert", brain)
        assertTrue(done.artifacts.isEmpty())
        assertTrue(done.attachments.isEmpty())
        assertTrue(ws.walk().any { it.extension == "png" })
    }

    @Test
    fun generatedImageIsAttachedByARealAttachCall() {
        val brain = ScriptedBrain(
            BrainDecision.Act(ToolCall.GenerateImage("c1", "water dripping in the desert", filename = "images/raw.png")),
            BrainDecision.Act(ToolCall.AttachFiles("c2", listOf(ToolCall.AttachSpec("images/raw.png", "Water in the desert")))),
            BrainDecision.Answer("Here it is."),
        )
        val (done, _) = run("make a picture of water dripping in the desert", brain)
        assertEquals(listOf("/images/Water in the desert.png"), done.artifacts.map { it.path })
    }

    @Test
    fun oldFileTheUserDidNotAskForIsRefused() {
        val brain = ScriptedBrain(
            BrainDecision.Act(ToolCall.AttachFiles("c1", listOf(ToolCall.AttachSpec("notes/old.txt")))),
            BrainDecision.Answer("ok"),
        )
        val (done, _) = run("what is 2 + 2?", brain) { it.write("notes/old.txt", "x") }
        assertTrue(done.artifacts.isEmpty())
        val result = brain.seen.last().observations.single().result
        assertFalse(result.ok)
        assertTrue("not attached" in result.output)
    }

    @Test
    fun oldFileIsAttachedWhenTheUserAsksOrMentionsIt() {
        val asked = ScriptedBrain(BrainDecision.Act(ToolCall.AttachFiles("c1", listOf(ToolCall.AttachSpec("notes/old.txt")))), BrainDecision.Answer("ok"))
        assertEquals(1, run("send me the notes file", asked) { it.write("notes/old.txt", "x") }.first.artifacts.size)
        val mentioned = ScriptedBrain(BrainDecision.Act(ToolCall.AttachFiles("c1", listOf(ToolCall.AttachSpec("notes/old.txt")))), BrainDecision.Answer("ok"))
        assertEquals(1, run("fix the typo in @notes/old.txt", mentioned) { it.write("notes/old.txt", "x") }.first.artifacts.size)
    }

    @Test
    fun policyRules() {
        assertNull(AttachPolicy.refusal("/a.mp4", "hi", producedThisTurn = true))
        assertNull(AttachPolicy.refusal("/imports/1/photo.jpg", "@imports/1/photo.jpg crop it", producedThisTurn = false))
        assertNull(AttachPolicy.refusal("/downloads/Cat video.mp4", "where is the cat video", producedThisTurn = false))
        assertNull(AttachPolicy.refusal("/report.pdf", "trimite-mi raportul", producedThisTurn = false))
        assertNotNull(AttachPolicy.refusal("/report.pdf", "what's the weather?", producedThisTurn = false))
    }
}
