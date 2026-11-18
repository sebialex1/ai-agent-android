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
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.TurnText
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import com.sebialex.agent.ui.chat.TurnSection
import com.sebialex.agent.ui.chat.turnSections
import com.sebialex.agent.ui.chat.upsertBlock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A turn reads in the order it happened: narration, the steps that follow it below, then the
 * next narration below them. The blocks that make this possible are one per model call,
 * upserted by id, and merged with steps into chronological sections.
 */
class TurnSectionsTest {

    @Test
    fun narrationStaysAboveTheStepsItLedTo() {
        val blocks = listOf(TurnText("b1", "Let me look.", at = 100))
        val steps = listOf(step("s1", 200), step("s2", 300))
        val sections = turnSections(blocks, steps)
        assertEquals(2, sections.size)
        assertTrue(sections[0] is TurnSection.Text)
        assertTrue(sections[1] is TurnSection.Steps)
        // Text after the steps: a second model call answers below the burst it caused.
        val later = turnSections(blocks + TurnText("b2", "All done.", at = 400), steps)
        assertEquals(listOf("b1", "b2"), later.filterIsInstance<TurnSection.Text>().map { it.block.id })
        assertTrue(later[0] is TurnSection.Text)
        assertTrue(later[1] is TurnSection.Steps)
        assertTrue(later[2] is TurnSection.Text)
    }

    @Test
    fun thinkingAndNarrationOfOneCallShowThinkingFirst() {
        // Same start time: the reasoning streamed before the content, so its row goes first.
        val sections = turnSections(listOf(TurnText("b1", "Text.", at = 100)), listOf(step("s1", 100)))
        assertTrue(sections[0] is TurnSection.Steps)
        assertTrue(sections[1] is TurnSection.Text)
    }

    @Test
    fun multipleStepSegmentsKeepTheirOrderAroundText() {
        val steps = listOf(step("s1", 100), step("s2", 200))
        val blocks = listOf(
            TurnText("b1", "between", at = 150),
            TurnText("b2", "end", at = 300),
        )
        val kinds = turnSections(blocks, steps).map { it::class.simpleName }
        assertEquals(listOf("Steps", "Text", "Steps", "Text"), kinds)
    }

    @Test
    fun everyBurstGetsItsOwnStepsBlockEvenWithoutThinkingRows() {
        // A model that narrates, acts, narrates again, acts again, answers — with no reasoning
        // step between the bursts: each burst must still be its own collapsible group.
        val steps = listOf(
            step("s1", 150), step("s2", 160),
            step("s3", 350), step("s4", 360),
            step("s5", 550),
        )
        val blocks = listOf(
            TurnText("b1", "Looking first.", at = 100),
            TurnText("b2", "Now the second part.", at = 300),
            TurnText("b3", "Last part.", at = 500),
            TurnText("b4", "All done.", at = 700),
        )
        val kinds = turnSections(blocks, steps).map { it::class.simpleName }
        assertEquals(listOf("Text", "Steps", "Text", "Steps", "Text", "Steps", "Text"), kinds)
    }

    @Test
    fun textWrittenInConsecutiveCallsWithoutActionsReadsAsOne() {
        val sections = turnSections(
            listOf(TurnText("b1", "First", at = 100), TurnText("b2", "Second", at = 200)),
            listOf(step("s1", 150)),
        )
        val kinds = sections.map { it::class.simpleName }
        assertEquals(listOf("Text", "Steps", "Text"), kinds)
    }

    @Test
    fun repeatedThinkingIsNotASecondRow() {
        val sections = turnSections(
            listOf(TurnText("b1", "Text", at = 300)),
            listOf(
                Step("t1", StepKind.REASONING, "Thinking", "reasoning", input = "", output = "Thinking", status = StepStatus.DONE, startedAt = 100),
                Step("t2", StepKind.REASONING, "Thinking", "reasoning", input = "", output = "  Thinking  ", status = StepStatus.DONE, startedAt = 200),
                step("s1", 250),
            ),
        )
        val kinds = sections.map { it::class.simpleName }
        assertEquals(listOf("Steps", "Text"), kinds)
        // Think and tool of the burst read as one collapsible group, duplicates off.
        assertEquals(2, (sections[0] as TurnSection.Steps).steps.size)
    }

    @Test
    fun noBlocksKeepsTheFixedLegacyLayout() {
        assertTrue(turnSections(emptyList(), listOf(step("s1", 1))).isEmpty())
    }

    @Test
    fun streamEventsFillOneBlockPerCall() {
        val empty = emptyList<TurnText>()
        val first = upsertBlock(empty, "b1", "First", 100)
        assertEquals(listOf(TurnText("b1", "First", 100)), first)
        // The same call's next delta replaces the block wholesale; the second call appends.
        val replaced = upsertBlock(first, "b1", "First part continued", 100)
        assertEquals(listOf(TurnText("b1", "First part continued", 100)), replaced)
        val second = upsertBlock(replaced, "b2", "Second", 200)
        assertEquals(listOf("b1", "b2"), second.map { it.id })
        // A blank first delta is never a placeholder, and an empty id changes nothing.
        assertEquals(second.size, upsertBlock(second, "b3", "   ", 300).size)
        assertEquals(second, upsertBlock(second, "", "x", 300))
    }

    @Test
    fun eachModelCallStreamsIntoItsOwnBlock() {
        val ws = Workspace(Files.createTempDirectory("blocks").toFile())
        val brain = object : AgentBrain {
            var calls = 0
            override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
                calls++
                if (calls == 1) {
                    ctx.onContent("Let me check the workspace first.")
                    return BrainDecision.Act(ToolCall.Invalid("c1", "unknown", "args", "not a tool"))
                }
                ctx.onContent("Checked: the workspace has nothing in it yet.")
                return BrainDecision.Answer("The workspace is empty.")
            }
        }
        val tools = ToolExecutor(ws, noShell, web = noWeb)
        val orchestrator = AgentOrchestrator(brain, tools, PermissionPolicy(), ws, audit = {})
        val events = runBlocking { orchestrator.run("look around", emptyList(), allow).toList() }
        val deltas = events.filterIsInstance<AgentEvent.ContentDelta>()
        val ids = deltas.map { it.block }.distinct()
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        // Timestamps of the two calls increase, so the second block sorts below the first steps.
        assertTrue(deltas.last().at >= deltas.first().at)
        val done = events.filterIsInstance<AgentEvent.Finished>().single()
        assertEquals("The workspace is empty.", done.text)
        assertEquals(ids.last(), done.block)
        // Applying the stream exactly as the chat does yields narration below, in order.
        var blocks = emptyList<TurnText>()
        deltas.forEach { blocks = upsertBlock(blocks, it.block, it.text, it.at) }
        blocks = upsertBlock(blocks, done.block, done.text, done.at)
        assertEquals(listOf("Let me check the workspace first.", "The workspace is empty."), blocks.map { it.text })
    }

    private fun step(id: String, at: Long) = Step(
        id, StepKind.SHELL, "cmd", "shell", "", status = StepStatus.DONE, startedAt = at,
    )

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
}
