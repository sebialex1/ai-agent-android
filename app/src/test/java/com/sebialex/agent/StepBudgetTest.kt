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
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The main agent loop has no tool-call ceiling by default: a long task (dozens of file reads,
 * edits, downloads \u2026) no longer ends with "I stopped after 30 tool calls without finishing."
 * Bounded callers (subagent children) still pass [com.sebialex.agent.domain.agent.SubagentRuns.CHILD_MAX_STEPS].
 */
class StepBudgetTest {

    /** A brain that records what it was told; every decision is scripted in order. */
    private class ScriptedBrain(val decisions: List<BrainDecision>) : AgentBrain {
        val nexts = AtomicInteger()
        val budgets = mutableListOf<Int>()
        override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
            budgets.add(ctx.stepBudget)
            val i = nexts.getAndIncrement()
            return decisions.getOrElse(i) { BrainDecision.Answer("done") }
        }

        override suspend fun endTurn(ctx: BrainContext) {}
    }

    private val noShell = object : Shell {
        override fun execute(command: String, cwd: String) = ShellResult("", 0, cwd)
    }
    private val noWeb = object : WebGateway {
        override suspend fun search(query: String, withImages: Boolean): List<SearchHit> = emptyList()
        override suspend fun fetch(url: String) = FetchedPage(url, "", "")
    }
    private val allow = object : ApprovalGate {
        override suspend fun request(request: ApprovalRequest) = true
    }

    private fun workspace() = Workspace(File.createTempFile("stepbudget", "").let { f -> f.delete(); f.mkdirs(); f })

    @Test
    fun theDefaultRunsPastTheOldThirtyCallCeiling() = runBlocking {
        // Forty scripted reads (one past the old ceiling), then the answer \u2014 with the default
        // orchestrator, i.e. the way the app builds it for real turns.
        val calls = (1..40).map { BrainDecision.Act(ToolCall.ListFiles(callId = "c$it")) }
        val brain = ScriptedBrain(calls + BrainDecision.Answer("all read"))
        val ws = workspace()
        val events = withTimeout(30_000) {
            AgentOrchestrator(brain, ToolExecutor(ws, noShell, web = noWeb), PermissionPolicy(), ws, audit = {})
                .run("hi", emptyList(), allow).toList()
        }
        // Every call ran and the turn ended with the real answer, not the give-up message.
        assertEquals("the brain saw every turn plus the answer", 41, brain.nexts.get())
        val finished = events.filterIsInstance<AgentEvent.Finished>().single()
        assertEquals("all read", finished.text)
        assertFalse(
            "the stop message must not appear for an uncapped run",
            finished.text.contains("tool calls"),
        )
        // The model is never told about a budget that does not exist (0 = no limit).
        assertTrue(
            "the server-side prompt carries no budget line for an uncapped run",
            brain.budgets.all { it == 0 },
        )
    }

    @Test
    fun anExplicitBudgetStillEndsTheRun() = runBlocking {
        // Ten calls into a five-call budget: give up, with the capped message (as before).
        val brain = ScriptedBrain(List(10) { i -> BrainDecision.Act(ToolCall.ListFiles(callId = "c$i")) })
        val ws = workspace()
        val events = withTimeout(30_000) {
            AgentOrchestrator(brain, ToolExecutor(ws, noShell, web = noWeb), PermissionPolicy(), ws, audit = {}, maxSteps = 5)
                .run("hi", emptyList(), allow).toList()
        }
        val finished = events.filterIsInstance<AgentEvent.Finished>().single()
        assertTrue(
            "a capped run still announces the give-up",
            finished.text.contains("I stopped after 5 tool calls"),
        )
        assertEquals("the brain was called only for the budgeted five", 5, brain.nexts.get())
    }
}
