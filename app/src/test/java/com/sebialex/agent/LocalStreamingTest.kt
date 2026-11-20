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
 * (BrainContext.onContent, fragment semantics like every other brain) instead of appearing only
 * when the whole answer is done. Everything runs against [FakeLmRuntime]; the fake's chunked
 * script delivers the answer delta by delta, exactly what the native MessageCallback does during
 * a real decode.
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

    @Test
    fun localAnswersArriveLiveDeltaByDeltaNotOnlyAtTheEnd() = runBlocking {
        val f = Fixture { _, _ -> LocalFakes.Script(chunks = listOf("Hel", "lo ", "world!")) }
        val fragments = mutableListOf<String>()
        val decision = f.brain().next(BrainContext("say hello", emptyList(), emptyList(), emptyList(), runId = "run-1", onContent = { fragments += it }))
        // The full answer was streamed out as fragments while the decode ran, in order.
        assertEquals(listOf("Hel", "lo ", "world!"), fragments)
        // The final decision matches what was streamed: the chat is never told twice.
        val answer = decision as BrainDecision.Answer
        assertEquals("Hello world!", answer.text)
        assertTrue(answer.thought == null)
        // And only one send ever reached the fake conversation.
        assertEquals(1, f.runtime.conversations.single().sent.size)
    }

    @Test
    fun streamingAlsoCoversToolCallStepsBetweenTheTexts() = runBlocking {
        // A turn that calls a tool between two texts still streams each text live.
        val f = Fixture { conversation, send ->
            if (conversation == 0 && send == 0) LocalFakes.Script(chunks = listOf("let me check", " the files "), calls = listOf("list_files" to mapOf("path" to "/")))
            else LocalFakes.Script(chunks = listOf("two", " files"))
        }
        val fragments = mutableListOf<String>()
        val first = f.brain().next(BrainContext("list them", emptyList(), emptyList(), emptyList(), runId = "run-1", onContent = { fragments += it }))
        assertTrue(first is BrainDecision.Act)
        assertEquals(listOf("let me check", " the files "), fragments)
        fragments.clear()
        val second = f.brain().next(
            BrainContext(
                "list them", emptyList(),
                listOf(com.sebialex.agent.domain.agent.Observation(
                    com.sebialex.agent.domain.model.ToolCall.ListFiles("local_1", "/"),
                    com.sebialex.agent.domain.model.ToolResult("local_1", true, "2 files"), 0,
                )),
                emptyList(), runId = "run-1", onContent = { fragments += it },
            ),
        )
        val answer = second as BrainDecision.Answer
        assertEquals("two files", answer.text)
        assertEquals(listOf("two", " files"), fragments)
    }
}
