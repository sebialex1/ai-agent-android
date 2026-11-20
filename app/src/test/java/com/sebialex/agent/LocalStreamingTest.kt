package com.sebialex.agent

import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModel
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The on-device brain streams its answer live: text deltas reach the chat while the decode runs
 * (BrainContext.onContent, fragment semantics like every other brain) and each callback also
 * reports a rough percent of the reply budget the text has used so far (BrainContext.onProgress).
 * Everything runs against [FakeLmRuntime]; the fake's chunked script delivers the answer delta by
 * delta, exactly what the native MessageCallback does during a real decode.
 */
class LocalStreamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A text-only model on a temp file, its engine on a fake runtime — the same shape as the
     * reuse test's fixture, kept here so both stay independently readable.
     */
    private class Fixture(script: (conversation: Int, send: Int) -> LocalFakes.Script) {
        val model = LocalModel(
            id = "local/test", name = "Test Model", tagline = "", fileName = "m.litertlm",
            maxContext = 8192, toolCalling = false, thinking = false,
        )
        private val file: File = File.createTempFile("stream", ".litertlm").apply { writeText("model"); deleteOnExit() }
        private val cacheDir: File = File.createTempFile("streamcache", "").let { f -> f.delete(); File(f, "c").apply { mkdirs() }; f }
        val runtime = FakeLmRuntime(script = script)
        val engine: LocalEngine = LocalEngine(
            runtime, { file }, cacheDir, { "gpu" }, { 4096 }, { false }, { null }, { "" }, CoroutineScope(Dispatchers.Default),
        )

        fun brain() = com.sebialex.agent.data.local.LocalBrain(engine, model.id, { model }, { false })
    }

    private fun ctx(
        fragments: MutableList<String> = mutableListOf(),
        pcts: MutableList<Int> = mutableListOf(),
    ) = BrainContext(
        "say hello", emptyList(), emptyList(), emptyList(),
        onContent = { fragments += it },
        onProgress = { pcts += it },
    )

    @Test
    fun localAnswersArriveLiveDeltaByDeltaNotOnlyAtTheEnd() = runBlocking {
        val f = Fixture { _, _ -> LocalFakes.Script(chunks = listOf("Hel", "lo ", "world!")) }
        val fragments = mutableListOf<String>()
        val pcts = mutableListOf<Int>()
        val decision = f.brain().next(ctx(fragments, pcts))
        // The full answer was streamed out as fragments while the decode ran, in order.
        assertEquals(listOf("Hel", "lo ", "world!"), fragments)
        // The final decision matches what was streamed: the chat is never told twice.
        val answer = decision as BrainDecision.Answer
        assertEquals("Hello world!", answer.text)
        assertTrue(answer.thought == null)
        // And nothing is left over in the fake's pending state.
        assertEquals(1, f.runtime.conversations.single().sent.size)
    }

    @Test
    fun streamingAlsoReportsARoughPercentOfTheReplyBudget() = runBlocking {
        val f = Fixture { _, _ -> LocalFakes.Script(chunks = listOf("a".repeat(1024), "b".repeat(1024), "c".repeat(300))) }
        val pcts = mutableListOf<Int>()
        f.brain().next(ctx(pcts = pcts))
        // The context is 4096, so the reply budget is (4096/8)*4 = 2048 characters: 1024 chars is
        // 50%, 2048 caps near-full. Never 100 — the text can always keep growing.
        assertEquals(listOf(50, 99, 99), pcts)
    }
}
