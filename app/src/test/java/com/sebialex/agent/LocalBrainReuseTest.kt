package com.sebialex.agent

import com.google.ai.edge.litertlm.Content
import com.sebialex.agent.data.local.EngineState
import com.sebialex.agent.data.local.LocalBrain
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModel
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.agent.Observation
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import com.sebialex.agent.domain.model.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * One conversation per turn: when a step continues the same run's conversation (and sends only
 * the new batch), when it rebuilds, and that nothing is left open on an answer, an error, Stop,
 * a model switch, a "too long" transcript or a rollover. Everything runs against [FakeLmRuntime].
 */
class LocalBrainReuseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A model with tools on a temp file, its engine on a fake runtime. [context] is the context
     * size the user picked; the model's own maximum is higher, so the setting wins.
     */
    private class Fixture(
        val runtime: FakeLmRuntime,
        context: Int = 4096,
        tools: Boolean = true,
        thinking: Boolean = false,
    ) {
        val model = LocalModel(
            id = "local/test", name = "Test Model", tagline = "", fileName = "m.litertlm",
            maxContext = 8192, toolCalling = tools, thinking = thinking,
        )
        private val file: File = File.createTempFile("brain", ".litertlm").apply { writeText("model"); deleteOnExit() }
        private val cacheDir: File = File.createTempFile("braincache", "").let { f -> f.delete(); File(f, "c").apply { mkdirs() }; f }
        val engine: LocalEngine = LocalEngine(
            runtime, { file }, cacheDir, { "gpu" }, { context }, { false }, CoroutineScope(Dispatchers.Default),
        )

        fun brain(think: () -> Boolean = { false }) = LocalBrain(engine, model.id, { model }, think)
    }

    private fun fixture(
        script: (conversation: Int, send: Int) -> LocalFakes.Script = listThenAnswer,
        context: Int = 4096,
        tools: Boolean = true,
        thinking: Boolean = false,
    ) = Fixture(FakeLmRuntime(script = script), context, tools, thinking)

    /** The usual turn: the first send of any conversation asks for list_files, then it answers. */
    private val listThenAnswer: (conversation: Int, send: Int) -> LocalFakes.Script = { _, send ->
        if (send == 0) LocalFakes.oneCall("list_files", mapOf("path" to "/")) else LocalFakes.answer()
    }

    private fun ctx(
        task: String = "list the files",
        runId: String = "run-1",
        history: List<Turn> = emptyList(),
        observations: List<Observation> = emptyList(),
    ) = BrainContext(task, history, observations, emptyList(), runId = runId)

    private fun listed(batch: Int, callId: String = "local_1", output: String = "2 files") =
        Observation(ToolCall.ListFiles(callId, "/"), ToolResult(callId, true, output), batch)

    private fun Fixture.conversation(i: Int) = runtime.conversations[i]

    // ------------------------------------------------------------------ continuation

    @Test
    fun firstStepBuildsTheFullTranscript() = runBlocking {
        val f = fixture()
        val history = listOf(
            Turn("t1", Role.USER, "hello there", createdAt = 0),
            Turn("t2", Role.ASSISTANT, "hi", createdAt = 0),
        )
        val decision = f.brain().next(ctx(history = history))
        assertTrue(decision is BrainDecision.Act)
        assertEquals("GPU", (f.engine.state.value as EngineState.Ready).model.backend)
        val conversation = f.conversation(0)
        // The task is the message sent now; the earlier turns are the initial messages.
        assertEquals(1, conversation.sent.size)
        val sent = conversation.sent.single()
        assertEquals(Role.USER.name, sent.role.name)
        val text = sent.contents.contents.single() as Content.Text
        assertEquals("list the files", text.text)
        assertEquals(2, conversation.config.initialMessages.size)
    }

    @Test
    fun secondStepSendsOnlyTheNewBatch() = runBlocking {
        val f = fixture()
        val first = f.brain().next(ctx())
        assertTrue(first is BrainDecision.Act)
        assertEquals("local_1", (first as BrainDecision.Act).calls.single().callId)
        val second = f.brain().next(ctx(observations = listOf(listed(batch = 1))))
        assertTrue(second is BrainDecision.Answer)
        // The same conversation, continued: nothing new was created.
        assertEquals(1, f.runtime.conversations.size)
        val conversation = f.conversation(0)
        // The second send is one tool message with only the new batch; the model's own
        // tool-call message is never re-sent.
        assertEquals(2, conversation.sent.size)
        val tool = conversation.sent[1]
        assertEquals("TOOL", tool.role.name)
        assertTrue(tool.toolCalls.isEmpty())
        val response = tool.contents.contents.single() as Content.ToolResponse
        assertEquals("list_files", response.name)
        assertEquals("2 files", response.response)
    }

    @Test
    fun threeStepsAllContinueIncrementally() = runBlocking {
        val f = fixture(script = { _, send ->
            if (send < 2) LocalFakes.oneCall("list_files", mapOf("path" to "/")) else LocalFakes.answer()
        })
        val first = f.brain().next(ctx())
        assertTrue(first is BrainDecision.Act)
        val second = f.brain().next(ctx(observations = listOf(listed(batch = 1))))
        assertTrue(second is BrainDecision.Act)
        assertEquals("local_2", (second as BrainDecision.Act).calls.single().callId)
        val third = f.brain().next(ctx(observations = listOf(
            listed(batch = 1),
            Observation(ToolCall.ListFiles("local_2", "/"), ToolResult("local_2", true, "4 files"), batch = 2),
        )))
        assertTrue(third is BrainDecision.Answer)
        // All three steps continued the one conversation: nothing was rebuilt mid-turn.
        assertEquals(1, f.runtime.conversations.size)
        assertEquals(3, f.conversation(0).sent.size)
    }

    @Test
    fun twoNewBatchesRebuildInsteadOfContinuing() = runBlocking {
        val f = fixture()
        f.brain().next(ctx())
        val second = f.brain().next(
            ctx(
                observations = listOf(
                    listed(batch = 1),
                    Observation(ToolCall.ListFiles("local_2", "/w"), ToolResult("local_2", true, "3 files"), batch = 2),
                ),
            ),
        )
        assertTrue(second is BrainDecision.Act)
        // Not the previous observations plus one batch: a rebuild, so a new conversation.
        assertEquals(2, f.runtime.conversations.size)
    }

    /** The turn that keeps going: every send of any conversation asks for list_files until the last, which answers. */
    private val keepCalling: (conversation: Int, send: Int) -> LocalFakes.Script = { _, send ->
        if (send < 2) LocalFakes.oneCall("list_files", mapOf("path" to "/")) else LocalFakes.answer()
    }

    @Test
    fun aChangedResultRebuildsInsteadOfContinuing() = runBlocking {
        val f = fixture(script = keepCalling)
        f.brain().next(ctx())
        val second = f.brain().next(ctx(observations = listOf(listed(batch = 1))))
        assertTrue(second is BrainDecision.Act)
        assertEquals(1, f.runtime.conversations.size)
        // The same already-sent call, but a different result than what was sent: a rebuild.
        val third = f.brain().next(ctx(observations = listOf(
            listed(batch = 1, output = "5 files"),
            Observation(ToolCall.ListFiles("local_2", "/"), ToolResult("local_2", true, "4 files"), batch = 2),
        )))
        assertTrue(third is BrainDecision.Act)
        assertEquals(2, f.runtime.conversations.size)
    }

    @Test
    fun changedErrorStatusRebuildsInsteadOfContinuing() = runBlocking {
        val f = fixture(script = keepCalling)
        f.brain().next(ctx())
        f.brain().next(ctx(observations = listOf(listed(batch = 1))))
        assertEquals(1, f.runtime.conversations.size)
        // The same output, but the tool failed this time: the rendered tool response changes,
        // so the already-sent observation no longer matches and the step rebuilds.
        val third = f.brain().next(ctx(observations = listOf(
            Observation(ToolCall.ListFiles("local_1", "/"), ToolResult("local_1", false, "2 files"), batch = 1),
            Observation(ToolCall.ListFiles("local_2", "/"), ToolResult("local_2", true, "4 files"), batch = 2),
        )))
        assertTrue(third is BrainDecision.Act)
        assertEquals(2, f.runtime.conversations.size)
    }

    @Test
    fun wrongToolResultIdRebuildsInsteadOfContinuing() = runBlocking {
        val f = fixture(script = keepCalling)
        f.brain().next(ctx())
        // The fresh batch answers a call id the model never made: a rebuild.
        val second = f.brain().next(ctx(observations = listOf(listed(batch = 1, callId = "local_9"))))
        assertTrue(second is BrainDecision.Act)
        assertEquals(2, f.runtime.conversations.size)
    }

    @Test
    fun missingToolResultIdRebuildsInsteadOfContinuing() = runBlocking {
        // The model asked for two calls; the next step answers only the first of them.
        val f = fixture(script = { _, send ->
            if (send == 0) LocalFakes.Script(calls = listOf(
                "list_files" to mapOf("path" to "/"),
                "search_files" to mapOf("query" to "x"),
            )) else LocalFakes.answer()
        })
        f.brain().next(ctx())
        val second = f.brain().next(ctx(observations = listOf(listed(batch = 1))))
        assertTrue(second is BrainDecision.Act)
        assertEquals(2, f.runtime.conversations.size)
    }

    @Test
    fun settingsChangeRebuildsInsteadOfContinuing() = runBlocking {
        val f = fixture(thinking = true)
        val first = f.brain(think = { false }).next(ctx())
        assertTrue(first is BrainDecision.Act)
        // The user turned thinking on mid-run: a different conversation is required.
        val second = f.brain(think = { true }).next(ctx(observations = listOf(listed(batch = 1))))
        assertTrue(second is BrainDecision.Act)
        assertEquals(2, f.runtime.conversations.size)
    }

    // ------------------------------------------------------------------ closing

    @Test
    fun answerClosesTheConversation() = runBlocking {
        val f = fixture(script = { _, _ -> LocalFakes.answer() })
        val decision = f.brain().next(ctx())
        assertTrue(decision is BrainDecision.Answer)
        assertTrue(f.conversation(0).closed.get())
    }

    @Test
    fun errorClosesTheConversation() = runBlocking {
        val f = fixture(script = { _, _ -> LocalFakes.Script(error = RuntimeException("the model broke")) })
        try {
            f.brain().next(ctx())
            fail("expected the step to fail")
        } catch (e: RuntimeException) {
            assertEquals("the model broke", e.message)
        }
        assertTrue(f.conversation(0).closed.get())
    }

    @Test
    fun stopClosesTheConversation() = runBlocking {
        val f = fixture(script = { _, _ -> LocalFakes.Script(hang = true) })
        val job = launch { f.brain().next(ctx()) }
        delay(200)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(f.conversation(0).closed.get())
    }

    @Test
    fun modelSwitchClosesTheConversation() = runBlocking {
        val f = fixture()
        f.brain().next(ctx())
        val runtime = f.runtime
        assertFalse(runtime.conversations.single().closed.get())
        // Another model takes the engine: the live conversation cannot survive it.
        val other = LocalModel("local/other", "Other Model", "", "other.litertlm", maxContext = 8192)
        f.engine.load(other)
        assertTrue(runtime.conversations.single().closed.get())
        assertTrue(runtime.engines.first().closed.get())
    }

    // ------------------------------------------------------------------ run identity

    @Test
    fun twoRunsSamePromptSecondCarriesOnIncrementally() = runBlocking {
        val f = fixture()
        val brainA = f.brain()
        val brainB = f.brain()
        val runtime = f.runtime
        // Run A starts and stops mid-turn (an answer with tool calls).
        val a1 = brainA.next(ctx(runId = "run-a"))
        assertTrue(a1 is BrainDecision.Act)
        // Run B asks exactly the same thing while A never finished.
        val b1 = brainB.next(ctx(runId = "run-b"))
        assertTrue(b1 is BrainDecision.Act)
        assertEquals(2, runtime.conversations.size)
        // A's late endTurn arrives after B took the slot: it must not close B's conversation.
        brainA.endTurn(ctx(runId = "run-a"))
        assertFalse(runtime.conversations[1].closed.get())
        // B carries on incrementally: no new conversation, only the new batch is sent.
        val b2 = brainB.next(ctx(runId = "run-b", observations = listOf(listed(batch = 1))))
        assertTrue(b2 is BrainDecision.Answer)
        assertEquals(2, runtime.conversations.size)
        assertEquals(2, runtime.conversations[1].sent.size)
        // B's own endTurn closes its conversation.
        brainB.endTurn(ctx(runId = "run-b", observations = listOf(listed(batch = 1))))
        assertTrue(runtime.conversations[1].closed.get())
    }

    @Test
    fun sameTextDifferentRunIdIsReleasedSeparately() = runBlocking {
        val f = fixture()
        val brainA = f.brain()
        brainA.next(ctx(runId = "run-a"))
        // Run A's endTurn releases its own conversation...
        brainA.endTurn(ctx(runId = "run-a"))
        val runtime = f.runtime
        assertTrue(runtime.conversations.single().closed.get())
        // ...and the same prompt in another run gets its own, still-open conversation.
        val brainB = f.brain()
        brainB.next(ctx(runId = "run-b"))
        assertFalse(runtime.conversations[1].closed.get())
    }

    @Test
    fun endTurnClosesTheTurnsConversation() = runBlocking {
        val f = fixture()
        f.brain().next(ctx())
        val runtime = f.runtime
        assertFalse(runtime.conversations.single().closed.get())
        f.brain().endTurn(ctx(observations = listOf(listed(batch = 1))))
        assertTrue(runtime.conversations.single().closed.get())
    }

    // ------------------------------------------------------------------ size limits

    @Test
    fun tooLongToolCallArgumentsFailTheStep() = runBlocking {
        val f = fixture(context = 2048)
        f.brain().next(ctx(task = "write it"))
        val runtime = f.runtime
        val big = "x".repeat(30_000)
        val observation = Observation(
            ToolCall.WriteFile("local_1", "big.txt", big),
            ToolResult("local_1", true, "written"), batch = 1,
        )
        try {
            f.brain().next(ctx(task = "write it", observations = listOf(observation)))
            fail("expected the step to fail")
        } catch (e: IOException) {
            assertEquals("this conversation is too long for Test Model's context window — start a new chat", e.message)
        }
        // No new conversation was created, and the old one is closed by then.
        assertEquals(1, runtime.conversations.size)
        assertTrue(runtime.conversations.single().closed.get())
    }

    @Test
    fun tooLongArgumentsWithAChangedKeyStillCloseTheOldConversation() = runBlocking {
        val f = fixture(context = 2048)
        f.brain().next(ctx(task = "write it"))
        val runtime = f.runtime
        val big = "x".repeat(30_000)
        val observation = Observation(
            ToolCall.WriteFile("local_1", "big.txt", big),
            ToolResult("local_1", true, "written"), batch = 1,
        )
        try {
            // A different history: the turn key changed, so a plain close(key) would miss the
            // old slot. The step still fails on the oversized arguments — with the old
            // conversation closed even though the key no longer matches.
            f.brain().next(ctx(
                task = "write it",
                history = listOf(Turn("t1", Role.USER, "earlier", createdAt = 0)),
                observations = listOf(observation),
            ))
            fail("expected the step to fail")
        } catch (e: IOException) {
            assertEquals("this conversation is too long for Test Model's context window — start a new chat", e.message)
        }
        // No new conversation was opened, and the old one is closed.
        assertEquals(1, runtime.conversations.size)
        assertTrue(runtime.conversations.single().closed.get())
    }

    @Test
    fun aValidBatchTooBigForTheLiveConversationIsNotSentIncrementally() = runBlocking {
        // Three calls answered by one batch, each with a huge output: the batch is valid
        // (correct pending call ids, one batch) and the live usage is still below the bound,
        // but sending it would push the conversation past two thirds of the 8K context — so it
        // is never sent incrementally, and the rebuild hits the hard limit instead.
        val f = fixture(context = 8192, script = { _, send ->
            if (send == 0) LocalFakes.Script(calls = listOf(
                "list_files" to mapOf("path" to "/"),
                "list_files" to mapOf("path" to "/w"),
                "list_files" to mapOf("path" to "/x"),
            )) else LocalFakes.answer()
        })
        val first = f.brain().next(ctx())
        assertEquals(3, (first as BrainDecision.Act).calls.size)
        assertEquals(1, f.runtime.conversations.size)
        val big = "o".repeat(9_000)
        val observations = listOf(
            Observation(ToolCall.ListFiles("local_1", "/"), ToolResult("local_1", true, big), batch = 1),
            Observation(ToolCall.ListFiles("local_2", "/w"), ToolResult("local_2", true, big), batch = 1),
            Observation(ToolCall.ListFiles("local_3", "/x"), ToolResult("local_3", true, big), batch = 1),
        )
        try {
            f.brain().next(ctx(observations = observations))
            fail("expected the step to fail")
        } catch (e: IOException) {
            assertEquals("this conversation is too long for Test Model's context window — start a new chat", e.message)
        }
        // The incremental path was not used: nothing beyond the first task message was ever
        // sent, and the live conversation is closed with nothing new opened.
        assertEquals(1, f.runtime.conversations.size)
        assertEquals(1, f.conversation(0).sent.size)
        assertTrue(f.conversation(0).closed.get())
    }

    @Test
    fun rebuildThatFitsAfterDroppingTheOldestExchangeCarriesOn() = runBlocking {
        val f = fixture(script = { _, _ -> LocalFakes.answer() }, context = 2048)
        f.brain().next(ctx(task = "look around"))
        // Two complete exchanges, each about 800 characters of output: too much for a 2K context
        // together with the fixed prompt, fine once the oldest exchange is dropped.
        val output = (1..100).joinToString("\n") { "file$it.txt" }
        val observations = listOf(
            Observation(ToolCall.ListFiles("local_1", "/"), ToolResult("local_1", true, output), batch = 1),
            Observation(ToolCall.SearchFiles("local_2", "needle"), ToolResult("local_2", true, output), batch = 2),
        )
        val second = f.brain().next(ctx(task = "look around", observations = observations))
        assertTrue(second is BrainDecision.Answer)
        val runtime = f.runtime
        // A new conversation was built, without the oldest exchange.
        assertEquals(2, runtime.conversations.size)
        val initial = runtime.conversations[1].config.initialMessages
        assertEquals(2, initial.size)
        // The latest exchange stays; the dropped one is gone.
        assertTrue(initial.any { m -> m.toolCalls.any { c -> c.name == "search_files" } })
        assertFalse(initial.any { m -> m.toolCalls.any { c -> c.name == "list_files" } })
    }

    @Test
    fun textOnlyModelWithALongTaskStillAnswers() = runBlocking {
        val f = fixture(script = { _, _ -> LocalFakes.answer() }, context = 2048, tools = false)
        val task = "Summarize this: " + "lorem ipsum dolor sit amet. ".repeat(100)
        val decision = f.brain().next(ctx(task = task))
        assertTrue(decision is BrainDecision.Answer)
        // No tools are configured or advertised, and the long task still fits.
        assertTrue(f.conversation(0).config.tools.isEmpty())
    }

    @Test
    fun longReasoningRollsOverTheLiveConversation() = runBlocking {
        // Every model reply carries ~4.5k characters of reasoning, and that stays in the live
        // conversation: after two steps the running usage passes two thirds of the 4K context.
        val reasoning = "r".repeat(4_500)
        val f = fixture(script = { _, send ->
            if (send < 2) LocalFakes.Script(thought = reasoning, calls = listOf("list_files" to mapOf("path" to "/")))
            else LocalFakes.answer()
        })
        val first = f.brain().next(ctx())
        assertTrue(first is BrainDecision.Act)
        val second = f.brain().next(ctx(observations = listOf(listed(batch = 1))))
        assertTrue(second is BrainDecision.Act)
        // The first two steps continued: still the same, still-open conversation.
        assertEquals(1, f.runtime.conversations.size)
        assertFalse(f.conversation(0).closed.get())
        val third = f.brain().next(ctx(observations = listOf(
            listed(batch = 1),
            Observation(ToolCall.ListFiles("local_2", "/"), ToolResult("local_2", true, "4 files"), batch = 2),
        )))
        assertTrue(third is BrainDecision.Act)
        // The live usage passed two thirds: the third step rolled over into a fresh conversation.
        assertEquals(2, f.runtime.conversations.size)
    }
}
