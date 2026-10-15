package com.sebialex.agent

import com.sebialex.agent.data.local.LocalBrain
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModel
import com.sebialex.agent.data.shell.Shell
import com.sebialex.agent.data.shell.ShellResult
import com.sebialex.agent.data.web.FetchedPage
import com.sebialex.agent.data.web.WebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.AgentOrchestrator
import com.sebialex.agent.domain.agent.ApprovalGate
import com.sebialex.agent.domain.agent.ApprovalRequest
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.agent.BrainRoute
import com.sebialex.agent.domain.agent.KeyPool
import com.sebialex.agent.domain.agent.RoutingBrain
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The end-of-turn hook: the orchestrator calls [AgentBrain.endTurn] once however the turn
 * ends, and [RoutingBrain] forwards it to the brain that served the run — even if the selected
 * model changed since.
 */
class EndTurnTest {

    /** A brain that records its calls; every decision is scripted in order. */
    private class RecordingBrain(val decisions: List<BrainDecision> = emptyList()) : AgentBrain {
        val nexts = AtomicInteger()
        val endTurns = AtomicInteger()
        override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
            val i = nexts.getAndIncrement()
            return decisions.getOrElse(i) { BrainDecision.Answer("done") }
        }

        override suspend fun endTurn(ctx: BrainContext) {
            endTurns.incrementAndGet()
        }
    }

    private class ThrowingBrain : AgentBrain {
        val endTurns = AtomicInteger()
        override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision =
            throw IllegalStateException("no answer")

        override suspend fun endTurn(ctx: BrainContext) {
            endTurns.incrementAndGet()
        }
    }

    private class HangingBrain : AgentBrain {
        val endTurns = AtomicInteger()
        override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision =
            awaitCancellation()

        override suspend fun endTurn(ctx: BrainContext) {
            endTurns.incrementAndGet()
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
    private val keys = object : KeyPool {
        override fun activeKey(provider: String) = "key"
        override fun keyCount(provider: String) = 1
        override fun rotateKey(provider: String, failed: String, reason: String) = null
    }

    private fun orchestrator(brain: AgentBrain) = run {
        val ws = Workspace(File.createTempFile("endturn", "").let { f -> f.delete(); f.mkdirs(); f })
        AgentOrchestrator(brain, ToolExecutor(ws, noShell, web = noWeb), PermissionPolicy(), ws, audit = {}, maxSteps = 5)
    }

    private fun ctx(runId: String) = BrainContext("hi", emptyList(), emptyList(), emptyList(), runId = runId)

    @Test
    fun endTurnIsCalledOnceHoweverTheTurnEnds() = runBlocking {
        // A turn that answers.
        val answering = RecordingBrain()
        withTimeout(10_000) { orchestrator(answering).run("hi", emptyList(), allow).toList() }
        assertEquals(1, answering.endTurns.get())
        // A turn that dies mid-step.
        val throwing = ThrowingBrain()
        runCatching { withTimeout(10_000) { orchestrator(throwing).run("hi", emptyList(), allow).toList() } }
        assertEquals(1, throwing.endTurns.get())
        // A turn that hits the step limit without answering.
        val looping = RecordingBrain(listOf(BrainDecision.Act(com.sebialex.agent.domain.model.ToolCall.ListFiles("c", "/"))))
        withTimeout(10_000) { orchestrator(looping).run("hi", emptyList(), allow).toList() }
        assertEquals(1, looping.endTurns.get())
    }

    @Test
    fun endTurnIsCalledWhenTheTurnIsStopped() = runBlocking {
        val brain = HangingBrain()
        val events = launch { withTimeout(30_000) { orchestrator(brain).run("hi", emptyList(), allow).toList() } }
        delay(300)
        events.cancel()
        // The finally runs even while the flow is being cancelled; poll for it.
        val until = System.currentTimeMillis() + 5_000
        while (brain.endTurns.get() == 0 && System.currentTimeMillis() < until) delay(50)
        assertEquals(1, brain.endTurns.get())
        withTimeout(5_000) { events.join() }
    }

    @Test
    fun routingBrainForwardsToEndBrainEvenAfterTheModelSwitched() = runBlocking {
        // An on-device model serves the run; the user then switches to a cloud model.
        val runtime = FakeLmRuntime(script = { _, send ->
            if (send == 0) LocalFakes.oneCall("list_files", mapOf("path" to "/")) else LocalFakes.answer()
        })
        val model = LocalModel("local/test", "Test Model", "", "m.litertlm", maxContext = 8192)
        val file = File.createTempFile("endturn", ".litertlm").apply { writeText("model"); deleteOnExit() }
        val cacheDir = File.createTempFile("endturn", "").let { f -> f.delete(); File(f, "c").apply { mkdirs() }; f }
        val engine = LocalEngine(runtime, { file }, cacheDir, { "gpu" }, { 4096 }, { false }, CoroutineScope(Dispatchers.Default))
        val local = LocalBrain(engine, model.id, { model }, { false })
        var selected = "local/test"
        val cloud = RecordingBrain()
        val routing = RoutingBrain(
            keys = keys,
            model = { selected },
            remote = { _, _ -> cloud },
            other = { m -> if (m == "local/test") BrainRoute("on-device model", local) else null },
        )
        val ctx = ctx("run-1")
        // The local model serves the run and asks for a tool: its conversation stays open.
        assertTrue(routing.next(ctx) is BrainDecision.Act)
        assertFalse(runtime.conversations.single().closed.get())
        assertEquals(0, cloud.endTurns.get())
        // The user switches to the cloud model before the turn's end arrives.
        selected = "openrouter/big"
        routing.endTurn(ctx)
        // Forwarded to the brain that served the run: the conversation is released, and the
        // cloud brain (which never served the run) sees nothing.
        assertTrue(runtime.conversations.single().closed.get())
        assertEquals(0, cloud.endTurns.get())
    }

    @Test
    fun aRunThatSwitchedToCloudReleasesBothBrains() = runBlocking {
        // The run starts on the on-device model, then the user switches to cloud mid-run: both
        // brains served it, so endTurn must reach both — the local conversation is released too.
        val runtime = FakeLmRuntime(script = { _, send ->
            if (send == 0) LocalFakes.oneCall("list_files", mapOf("path" to "/")) else LocalFakes.answer()
        })
        val model = LocalModel("local/test", "Test Model", "", "m.litertlm", maxContext = 8192)
        val file = File.createTempFile("endturn", ".litertlm").apply { writeText("model"); deleteOnExit() }
        val cacheDir = File.createTempFile("endturn", "").let { f -> f.delete(); File(f, "c").apply { mkdirs() }; f }
        val engine = LocalEngine(runtime, { file }, cacheDir, { "gpu" }, { 4096 }, { false }, CoroutineScope(Dispatchers.Default))
        val local = LocalBrain(engine, model.id, { model }, { false })
        var selected = "local/test"
        val cloud = RecordingBrain()
        val routing = RoutingBrain(
            keys = keys,
            model = { selected },
            remote = { _, _ -> cloud },
            other = { m -> if (m == "local/test") BrainRoute("on-device model", local) else null },
        )
        val ctx = ctx("run-1")
        // The local model serves the run and asks for a tool: its conversation stays open.
        assertTrue(routing.next(ctx) is BrainDecision.Act)
        assertFalse(runtime.conversations.single().closed.get())
        // The user switches to the cloud model, which serves the rest of the run.
        selected = "openrouter/big"
        assertTrue(routing.next(ctx) is BrainDecision.Answer)
        assertFalse(runtime.conversations.single().closed.get())
        assertEquals(0, cloud.endTurns.get())
        // The turn ends: the cloud brain's endTurn runs, and the local conversation is closed.
        routing.endTurn(ctx)
        assertTrue(runtime.conversations.single().closed.get())
        assertEquals(1, cloud.endTurns.get())
    }

    @Test
    fun routingBrainEndTurnIsOnceAndUnknownRunsAreIgnored() = runBlocking {
        val local = RecordingBrain()
        var selected = "local/test"
        val routing = RoutingBrain(
            keys = keys,
            model = { selected },
            remote = { _, _ -> RecordingBrain() },
            other = { m -> if (m == "local/test") BrainRoute("on-device model", local) else null },
        )
        // A run id nothing served: no-op.
        routing.endTurn(ctx("nobody"))
        assertEquals(0, local.endTurns.get())
        val ctx = ctx("run-1")
        assertTrue(routing.next(ctx) is BrainDecision.Answer)
        routing.endTurn(ctx)
        assertEquals(1, local.endTurns.get())
        // The entry is gone: a second endTurn does not forward again.
        routing.endTurn(ctx)
        assertEquals(1, local.endTurns.get())
    }
}
