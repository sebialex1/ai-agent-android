package com.sebialex.agent

import com.sebialex.agent.data.llm.ResponsesBrain
import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Responses API answers a request's text three times over the wire: as `output_text.delta`
 * events, again in the finished `message` item, and once more when `response.completed` replays its
 * whole `output` array. Only the deltas may count, or the answer reads "Hi!Hi!Hi!".
 */
class ResponsesBrainTest {

    /** One POST, one answer. The request is drained in bulk (per-char reads crawl in the sandbox). */
    private class FakeResponses(private val stream: String, private val type: String = "text/event-stream") {
        val socket = ServerSocket(0)

        fun port() = socket.localPort

        fun startServing(): FakeResponses {
            thread(isDaemon = true) {
                while (true) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: return@thread
                    try {
                        conn.use { c ->
                            val input = c.getInputStream()
                            // Raw header scan: stop at the blank line, then bulk-read the body.
                            val head = StringBuilder()
                            var prev = -1
                            while (true) {
                                val b = input.read()
                                if (b < 0) break
                                head.append(b.toChar())
                                if (prev == '\r'.code && b == '\n'.code && head.endsWith("\r\n\r\n")) break
                                prev = b
                            }
                            var contentLength = 0
                            for (h in head.lines()) {
                                if (h.lowercase().startsWith("content-length:")) contentLength = h.substringAfter(':').trim().toInt()
                            }
                            val buf = ByteArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val n = input.read(buf, read, contentLength - read)
                                if (n < 0) break
                                read += n
                            }
                            val body = stream.toByteArray(Charsets.UTF_8)
                            c.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.ISO_8859_1),
                            )
                            c.getOutputStream().write(body)
                        }
                    } catch (_: Exception) {
                        return@thread
                    }
                }
            }
            return this
        }

        fun shutdown() = socket.close()
    }

    private fun brain(port: Int) = ResponsesBrain(
        apiKey = "k", model = "m", endpoint = "http://localhost:$port/v1/responses",
        withTools = false, provider = "Fake",
    )

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    @Test
    fun streamedTextIsNotAppendedAgainByTheFinalEvents() = runBlocking {
        val stream = sse(
            """{"type":"response.reasoning_summary_text.delta","delta":"Think"}""",
            """{"type":"response.output_text.delta","delta":"Hi!"}""",
            // The finished message item repeats the full text…
            """{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"output_text","text":"Hi!"}]}}""",
            """{"type":"response.output_item.done","item":{"type":"reasoning","summary":[{"type":"summary_text","text":"Think"}]}}""",
            // …and `response.completed` repeats it once more in its output array.
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hi!"}]},{"type":"reasoning","summary":[{"type":"summary_text","text":"Think"}]}]}}""",
        )
        val fake = FakeResponses(stream)
        fake.startServing()
        try {
            val decision = brain(fake.port()).next(BrainContext("hi", emptyList(), emptyList(), emptyList())) {}
            assertTrue(decision is BrainDecision.Answer)
            assertEquals("Hi!", (decision as BrainDecision.Answer).text)
            assertEquals("Think", decision.thought?.text)
        } finally {
            fake.shutdown()
        }
    }

    @Test
    fun aWholeCallInItsAddedItemIsNotAppendedTwice() = runBlocking {
        val stream = sse(
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc1","call_id":"call_1","name":"web_search","arguments":"{\"query\":\"cats\"}"}}""",
            // The same item returns complete on `done` and again inside `response.completed`.
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc1","call_id":"call_1","name":"web_search","arguments":"{\"query\":\"cats\"}"}}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","id":"fc1","call_id":"call_1","name":"web_search","arguments":"{\"query\":\"cats\"}"}]}}""",
        )
        val fake = FakeResponses(stream)
        fake.startServing()
        try {
            val brain: AgentBrain = brain(fake.port())
            val decision = brain.next(BrainContext("find cats", emptyList(), emptyList(), emptyList())) {}
            assertTrue(decision is BrainDecision.Act)
            val calls = (decision as BrainDecision.Act).calls
            assertEquals(1, calls.size)
            assertEquals("web_search", calls.single().let { com.sebialex.agent.data.llm.OpenRouterBrain.toolName(it) })
        } finally {
            fake.shutdown()
        }
    }

    @Test
    fun aNonStreamingAnswerStillReadsOnce() = runBlocking {
        // No SSE at all: the whole body is one JSON document.
        val body = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hello."}]}]}"""
        val fake = FakeResponses(body)
        fake.startServing()
        try {
            val decision = brain(fake.port()).next(BrainContext("hi", emptyList(), emptyList(), emptyList())) {}
            assertEquals("Hello.", (decision as BrainDecision.Answer).text)
        } finally {
            fake.shutdown()
        }
    }
}
