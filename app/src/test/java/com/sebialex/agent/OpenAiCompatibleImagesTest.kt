package com.sebialex.agent

import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.llm.OpenAiCompatibleImages
import com.sebialex.agent.data.llm.modelImageName
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.agent.KeyPool
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

    /** A one-connection-at-a-time HTTP server: [respond] answers each POST request it received. */
    private class FakeImages(
        private val respond: (Int) -> Triple<Int, String, ByteArray> = {
            Triple(200, "image/png", byteArrayOf(1, 2, 3))
        },
    ) {
        val served = AtomicInteger(0)
        val socket = ServerSocket(0)

        /** The JSON body of each POST, in request order. */
        val bodies = mutableListOf<String>()

        fun port(): Int = socket.localPort

        fun start() {
            thread(isDaemon = true) {
                while (true) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: return@thread
                    try {
                        conn.use {
                            // Read raw bytes so a UTF-8 request body survives header parsing.
                            val input = java.io.BufferedInputStream(it.getInputStream())
                            val head = StringBuilder()
                            var prev = -1
                            while (true) {
                                val b = input.read()
                                if (b < 0) break
                                head.append(b.toChar())
                                if (prev == '\r'.code && b == '\n'.code && head.endsWith("\r\n\r\n")) break
                                prev = b
                            }
                            // Buffer may hold the first body bytes already (buffered read-ahead),
                            // so parse `len` from the *byte* stream position, not the reader.
                            var len = 0
                            for (h in head.lines()) {
                                if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toInt()
                            }
                            val requestBody = ByteArray(len).also { buf ->
                                var read = 0
                                while (read < len) read += input.read(buf, read, len - read)
                            }
                            synchronized(bodies) { bodies.add(String(requestBody, Charsets.UTF_8)) }
                            served.incrementAndGet()
                            val (code, type, body) = respond(served.get())
                            it.getOutputStream().write(
                                ("HTTP/1.1 $code ${if (code == 200) "OK" else "Bad Gateway"}\r\n" +
                                    "Content-Type: $type\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                            )
                            if (body.isNotEmpty()) it.getOutputStream().write(body)
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

    @Test fun theGeminiPresetDropsChatTierSuffixesFromTheImageModel() = runBlocking {
        val fake = FakeImages({ Triple(200, "application/json", """{"data":[{"b64_json":"AQID","media_type":"image/png"}]}""".toByteArray()) })
        fake.start()
        try {
            val img = OpenAiCompatibleImages(
                noKeys,
                provider("p", "http://localhost:${fake.port()}/v1", CustomProviders.GEMINI_ACCOUNT_PRESET),
                "gemini-3-pro-plus",
            )
            img.generate("a fox", null)
            assertTrue(fake.bodies.single(), fake.bodies.single().contains("\"model\":\"gemini-3-pro\""))
        } finally {
            fake.stop()
        }
    }

    @Test fun otherProvidersKeepTheModelNameVerbatim() = runBlocking {
        val fake = FakeImages({ Triple(200, "application/json", """{"data":[{"b64_json":"AQID","media_type":"image/png"}]}""".toByteArray()) })
        fake.start()
        try {
            val img = OpenAiCompatibleImages(
                noKeys,
                provider("p", "http://localhost:${fake.port()}/v1", "custom"),
                "google/gemini-3.1-flash-image",
            )
            img.generate("a fox", null)
            assertTrue(fake.bodies.single(), fake.bodies.single().contains("\"model\":\"google/gemini-3.1-flash-image\""))
        } finally {
            fake.stop()
        }
    }

    @Test fun theShimsOwnReasonIsForwardedInsteadOfTheQuotaGuess() = runBlocking {
        val detail = """{"detail":"no image from profile(s) u/0 \u2014 usually the daily image quota. Model said: sure, here is your image!"}"""
        val fake = FakeImages({ Triple(502, "application/json", detail.toByteArray()) })
        fake.start()
        try {
            val img = OpenAiCompatibleImages(
                noKeys,
                provider("p", "http://localhost:${fake.port()}/v1", CustomProviders.GEMINI_ACCOUNT_PRESET),
                "gemini-3-flash-plus",
            )
            try {
                img.generate("another fox", null)
                org.junit.Assert.fail("expected the failure to propagate")
            } catch (e: java.io.IOException) {
                assertTrue(e.message, e.message!!.contains("HTTP 502"))
                assertTrue(e.message, e.message!!.contains("Model said: sure, here is your image!"))
                // The shim explained itself, so the app's own quota guess should stay out of the way.
                assertTrue(e.message, !e.message!!.contains("the account's daily image quota may be spent"))
            }
        } finally {
            fake.stop()
        }
    }

    @Test fun theImageNameKeepsItsBaseAndThinkingTierButNoChatTiers() {
        assertEquals("gemini-3-pro", modelImageName("gemini-3-pro-plus"))
        assertEquals("gemini-3-flash", modelImageName(" gemini-3-flash-plus "))
        assertEquals("gemini-3-pro", modelImageName("gemini-3-pro-advanced"))
        assertEquals("gemini-3-flash-thinking", modelImageName("gemini-3-flash-thinking-plus"))
        assertEquals("gemini-3-pro", modelImageName("google/gemini-3-pro-plus"))
        assertEquals("gemini-3-pro", modelImageName("gemini-3-pro"))
        assertEquals("gpt-image-1", modelImageName("gpt-image-1"))
    }
}
