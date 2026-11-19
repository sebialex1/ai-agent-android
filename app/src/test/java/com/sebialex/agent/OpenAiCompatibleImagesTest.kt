package com.sebialex.agent

import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.llm.OpenAiCompatibleImages
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.agent.KeyPool
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Image generation through a custom provider: retry on a gateway hiccup, and the shim diagnosis. */
class OpenAiCompatibleImagesTest {

    private val noKeys = object : KeyPool {
        override fun activeKey(provider: String) = null
        override fun keyCount(provider: String) = 0
        override fun rotateKey(provider: String, failed: String, reason: String) = null
    }

    private fun provider(id: String, url: String, preset: String) = SecureSettings.CustomProvider(
        id = id, name = id, baseUrl = url, preset = preset,
    )

    /** A one-connection-at-a-time HTTP server: [respond] answers each POST request. */
    private class FakeImages(
        private val respond: (Int) -> Triple<Int, String, ByteArray> = {
            Triple(200, "image/png", byteArrayOf(1, 2, 3))
        },
    ) {
        val served = AtomicInteger(0)
        val socket = ServerSocket(0)

        fun port(): Int = socket.localPort

        fun start() {
            thread(isDaemon = true) {
                while (true) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: return@thread
                    try {
                        conn.use {
                            val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                            var line = reader.readLine()
                            var len = 0
                            while (!line.isNullOrEmpty()) {
                                val h = line.lowercase()
                                if (h.startsWith("content-length:")) len = line.substringAfter(':').trim().toInt()
                                line = reader.readLine()
                            }
                            if (len > 0) repeat(len) { reader.read() }
                            served.incrementAndGet()
                            val (code, type, body) = respond(served.get())
                            it.getOutputStream().write(
                                ("HTTP/1.1 $code ${if (code == 200) "OK" else "Bad Gateway"}\r\n" +
                                    "Content-Type: $type\r\n" +
                                    (if (code == 200) "Content-Length: ${body.size}\r\n" else "Content-Length: 0\r\n") +
                                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                            )
                            if (code == 200 && body.isNotEmpty()) it.getOutputStream().write(body)
                        }
                    } catch (_: Exception) {
                        return@thread
                    }
                }
            }
        }

        fun stop() = socket.close()
    }

    @Test fun aGatewayHiccupIsRetriedOnce() = runBlocking {
        val fake = FakeImages({ n ->
            if (n <= 1) Triple(502, "text/plain", byteArrayOf())
            else Triple(200, "application/json", """{"data":[{"b64_json":"AQID","media_type":"image/png"}]}""".toByteArray())
        })
        fake.start()
        try {
            val img = OpenAiCompatibleImages(
                noKeys,
                provider("p", "http://localhost:${fake.port()}/v1", "custom"),
                "gemini-3-pro-plus",
            )
            val result = img.generate("a fox in the snow", null)
            assertArrayEquals(byteArrayOf(1, 2, 3), result.bytes)
            assertEquals(2, fake.served.get()) // one failure swallowed, then success
        } finally {
            fake.stop()
        }
    }

    @Test fun aShimFailureKeepsFailingLoudlyWithTheQuotaDiagnosis() = runBlocking {
        val fake = FakeImages({ Triple(502, "text/plain", byteArrayOf()) })
        fake.start()
        try {
            val img = OpenAiCompatibleImages(
                noKeys,
                provider("p", "http://localhost:${fake.port()}/v1", CustomProviders.GEMINI_ACCOUNT_PRESET),
                "gemini-3-pro-plus",
            )
            try {
                img.generate("another fox", null)
                org.junit.Assert.fail("expected the failure to propagate")
            } catch (e: java.io.IOException) {
                assertTrue(e.message, e.message!!.contains("HTTP 502"))
                assertTrue(e.message, e.message!!.contains("daily image quota"))
                assertTrue(e.message, e.message!!.contains("Gemini (my account)"))
            }
        } finally {
            fake.stop()
        }
    }
}
