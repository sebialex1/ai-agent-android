package com.sebialex.agent

import com.sebialex.agent.data.llm.PollinationsImages
import com.sebialex.agent.data.llm.PollinationsModels
import com.sebialex.agent.domain.agent.KeyPool
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image generation through Pollinations: the no-key free path (the classic URL works anonymously),
 * the optional key (Authorization on gen.pollinations.ai with the model id), and the honest
 * failure texts for the service's 402/401/403 answers.
 */
class PollinationsImagesTest {

    private class NoKeys : KeyPool {
        override fun activeKey(provider: String) = null
        override fun keyCount(provider: String) = 0
        override fun rotateKey(provider: String, failed: String, reason: String) = null
    }

    private class OneKey(private val key: String) : KeyPool {
        override fun activeKey(provider: String) = key
        override fun keyCount(provider: String) = 1
        override fun rotateKey(provider: String, failed: String, reason: String) = null
    }

    /** A one-connection-at-a-time HTTP server answering each GET the same way. */
    private class FakePollinations(
        private val code: Int,
        private val type: String = "image/jpeg",
        private val body: ByteArray = byteArrayOf(9, 8, 7, 6),
    ) {
        val socket = ServerSocket(0)
        /** The request line of the last request ("GET /prompt/... HTTP/1.1"). */
        val requestLine = AtomicReference("")
        /** All request header lines of the last request. */
        val headers = AtomicReference("")

        fun root(): String = "http://localhost:${socket.localPort}"

        fun start() {
            thread(isDaemon = true) {
                while (true) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: return@thread
                    try {
                        conn.use {
                            val input = BufferedInputStream(it.getInputStream())
                            val first = readLine(input)
                            val head = buildString {
                                while (true) {
                                    val line = readLine(input)
                                    if (line.isNullOrEmpty()) break
                                    append(line).append('\n')
                                }
                            }
                            requestLine.set(first)
                            headers.set(head)
                            val status = when (code) { 200 -> "OK"; else -> "Error" }
                            it.getOutputStream().write(
                                ("HTTP/1.1 $code $status\r\n" +
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

        private fun readLine(input: BufferedInputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }
    }

    @Test fun noKeyGeneratesOnTheFreeEndpointWithoutAuth() = runBlocking {
        val fake = FakePollinations(200)
        fake.start()
        try {
            val img = PollinationsImages(NoKeys(), PollinationsModels.FREE_MODEL, classicRoot = fake.root(), genRoot = fake.root() + "/gen", seed = { 42 })
            val out = img.generate("a fox in the snow", "16:9")
            assertArrayEquals(byteArrayOf(9, 8, 7, 6), out.bytes)
            assertEquals("image/jpeg", out.mime)
            val line = fake.requestLine.get()
            assertTrue(line, line.startsWith("GET /prompt/a%20fox%20in%20the%20snow?"))
            assertTrue(line, "width=768" in line && "height=432" in line && "seed=42" in line)
            assertTrue(line, "Authorization" !in fake.headers.get())
        } finally {
            fake.stop()
        }
    }

    @Test fun aKeyRunsThePaidCatalogWithTheModelId() = runBlocking {
        val fake = FakePollinations(200)
        fake.start()
        try {
            val img = PollinationsImages(OneKey("sk_test"), "black-forest-labs/flux.1.1-pro", classicRoot = fake.root() + "/classic", genRoot = fake.root(), seed = { 7 })
            img.generate("a lighthouse", null)
            val line = fake.requestLine.get()
            assertTrue(line, line.startsWith("GET /image/a%20lighthouse?"))
            assertTrue(line, "model=black-forest-labs%2Fflux.1.1-pro" in line)
            assertTrue(fake.headers.get(), "Authorization: Bearer sk_test" in fake.headers.get())
        } finally {
            fake.stop()
        }
    }

    @Test fun fluxStaysFreeEvenWithAKeySaved() = runBlocking {
        val fake = FakePollinations(200)
        fake.start()
        try {
            val img = PollinationsImages(OneKey("sk_test"), PollinationsModels.FREE_MODEL, classicRoot = fake.root(), genRoot = fake.root() + "/gen", seed = { 1 })
            img.generate("a kite", null)
            assertTrue(fake.requestLine.get(), fake.requestLine.get().startsWith("GET /prompt/"))
        } finally {
            fake.stop()
        }
    }

    @Test fun theRateLimitAnswerSaysHowToLiftIt() = runBlocking {
        val fake = FakePollinations(402, type = "application/json",
            body = """{"success":false,"error":{"message":"rate limited","code":"RATE_LIMITED"}}""".toByteArray())
        fake.start()
        try {
            val img = PollinationsImages(NoKeys(), PollinationsModels.FREE_MODEL, classicRoot = fake.root(), genRoot = fake.root())
            val e = runBlocking { runCatching { img.generate("x", null) } }.exceptionOrNull()
            assertTrue(e!!.message, e.message!!.contains("every 45 s"))
            assertTrue(e.message, e.message!!.contains("Settings > Models > Pollinations"))
        } finally {
            fake.stop()
        }
    }

    @Test fun aRefusedPromptSaysTheFilterDeclinedIt() = runBlocking {
        val fake = FakePollinations(403, type = "application/json",
            body = """{"error":{"message":"unsafe"}}""".toByteArray())
        fake.start()
        try {
            val img = PollinationsImages(NoKeys(), PollinationsModels.FREE_MODEL, classicRoot = fake.root(), genRoot = fake.root())
            val e = runBlocking { runCatching { img.generate("x", null) } }.exceptionOrNull()
            assertTrue(e!!.message, e.message!!.contains("safety filter"))
            assertTrue(e.message, e.message!!.contains("unsafe"))
        } finally {
            fake.stop()
        }
    }

    @Test fun aBadSavedKeyPointsAtItsSetting() = runBlocking {
        val fake = FakePollinations(401, type = "application/json",
            body = """{"error":{"message":"nope"}}""".toByteArray())
        fake.start()
        try {
            val img = PollinationsImages(OneKey("bad"), "black-forest-labs/flux.2-flex", classicRoot = fake.root() + "/c", genRoot = fake.root())
            val e = runBlocking { runCatching { img.generate("x", null) } }.exceptionOrNull()
            assertTrue(e!!.message, e.message!!.contains("rejected the saved key"))
            assertTrue(e.message, e.message!!.contains("remove it to use the free no-key tier"))
        } finally {
            fake.stop()
        }
    }

    @Test fun sizesStayInsideTheFreeEndpointsBox() {
        assertEquals(768 to 768, PollinationsImages.sizeFor(null))
        assertEquals(768 to 768, PollinationsImages.sizeFor("banana"))
        assertEquals(768 to 768, PollinationsImages.sizeFor("0:5"))
        assertEquals(768 to 432, PollinationsImages.sizeFor("16:9"))
        assertEquals(432 to 768, PollinationsImages.sizeFor("9:16"))
        assertEquals(768 to 768, PollinationsImages.sizeFor("1:1"))
    }

    @Test fun thePickerListsTheFreeModelFirstAndRoutable() {
        val models = PollinationsModels.imageModels()
        assertTrue(models.isNotEmpty())
        assertEquals("flux", models.first().ref.model)
        assertTrue(models.all { it.ref.provider == com.sebialex.agent.data.llm.MediaModels.PROVIDER_POLLINATIONS })
        assertTrue("free" in models.first().source.lowercase())
    }
}
