package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.preview.PreviewLifecycle
import com.sebialex.agent.data.preview.PreviewServer
import com.sebialex.agent.data.preview.StaticFiles
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.ToolCall
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files

/** The web preview: the local server's files, types and refusals, and when it runs. */
class PreviewServerTest {
    private lateinit var base: File
    private lateinit var app: File
    private lateinit var server: PreviewServer
    private var port = 0

    @Before fun setUp() {
        base = Files.createTempDirectory("preview").toFile()
        app = File(base, "app").apply { mkdirs() }
        File(app, "index.html").writeText("<h1>hi</h1>")
        File(app, "app.js").writeText("console.log(1)")
        File(app, "style.css").writeText("body{}")
        File(app, "sub").mkdirs()
        File(app, "sub/index.html").writeText("sub")
        File(app, "my page.html").writeText("spaced")
        File(base, "secret.txt").writeText("top secret")
        server = PreviewServer(app)
        port = server.start()
    }

    @After fun tearDown() {
        server.stop()
        base.deleteRecursively()
    }

    private fun get(path: String, method: String = "GET"): Triple<Int, String?, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return Triple(code, c.contentType, body)
    }

    /** A request line sent as is, so the client cannot normalize the path first. */
    private fun raw(target: String): String = Socket("127.0.0.1", port).use { s ->
        s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    @Test fun servesIndexAndFilesWithTheirTypes() {
        assertEquals(Triple(200, "text/html; charset=utf-8", "<h1>hi</h1>"), get("/"))
        assertEquals("text/javascript; charset=utf-8", get("/app.js").second)
        assertEquals("text/css; charset=utf-8", get("/style.css?v=2").second)
        assertEquals("sub", get("/sub/").third)
        assertEquals("spaced", get("/my%20page.html").third)
    }

    @Test fun foldersWithoutSlashRedirectAndMissingIs404() {
        val c = URL("http://127.0.0.1:$port/sub").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        assertEquals(301, c.responseCode)
        assertEquals("/sub/", c.getHeaderField("Location"))
        assertEquals(404, get("/nope.js").first)
        assertEquals("no-store", c.getHeaderField("Cache-Control"))
    }

    @Test fun nothingOutsideTheFolderIsServed() {
        for (target in listOf("/../secret.txt", "/%2e%2e/secret.txt", "/sub/..%2f..%2fsecret.txt", "/..\\secret.txt", "/%2e%2e%5csecret.txt")) {
            val response = raw(target)
            assertTrue(target + ": " + response.lineSequence().first(), response.startsWith("HTTP/1.1 403"))
            assertFalse("top secret" in response)
        }
        assertTrue(raw("/%zz").startsWith("HTTP/1.1 403"))
    }

    @Test fun symlinksOutOfTheFolderAreRefused() {
        val link = File(app, "leak.txt").toPath()
        runCatching { Files.createSymbolicLink(link, File(base, "secret.txt").toPath()) }.onFailure { return }
        assertEquals(StaticFiles.Resolution.Forbidden, StaticFiles.resolve(app, "/leak.txt"))
    }

    @Test fun headHasNoBodyAndOtherMethodsAreRejected() {
        val (code, _, body) = get("/", "HEAD")
        assertEquals(200, code)
        assertEquals("", body)
        assertEquals(405, get("/", "POST").first)
    }

    @Test fun mimeTypes() {
        assertEquals("image/svg+xml", StaticFiles.mimeOf("logo.SVG"))
        assertEquals("application/wasm", StaticFiles.mimeOf("a.wasm"))
        assertEquals("application/json; charset=utf-8", StaticFiles.mimeOf("data.json"))
        assertEquals("application/octet-stream", StaticFiles.mimeOf("blob"))
    }

    @Test fun restartsOnTheSamePortWhenFree() {
        val first = port
        server.stop()
        assertNull(server.port)
        // A busy machine (a shared CI runner) can hand the port to another process between the
        // stop and the start; the contract is only that a FREE port is kept. The port is probed
        // the same way the server binds it, so the same-port path is asserted exactly when it
        // can hold, and the fallback path is covered when it cannot.
        val free = bindable(first)
        val restarted = server.start(first)
        if (free) assertEquals(first, restarted)
        assertEquals(200, get("/").first)
    }

    /** True when [port] can be bound right now, probed exactly like the server binds it. */
    private fun bindable(port: Int): Boolean = ServerSocket().use { s ->
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(PreviewServer.ADDRESS, port), 32)
            true
        } catch (_: IOException) {
            false
        }
    }

    @Test fun bindsTheIpv4LoopbackTheWindowLoads() {
        // Android's getLoopbackAddress() is ::1, which refuses connections to 127.0.0.1: the
        // server must bind exactly the address the WebView dials, on every platform.
        assertTrue(PreviewServer.ADDRESS is Inet4Address)
        assertEquals(PreviewServer.HOST, PreviewServer.ADDRESS.hostAddress)
        Socket(PreviewServer.HOST, port).use { assertTrue(it.isConnected) }
    }

    @Test fun quickStopAndStartNeverLeavesADeadServer() {
        // An old accept loop winding down must not close the socket of the next start.
        repeat(20) {
            server.stop()
            port = server.start(port)
            assertEquals(port, server.port)
            assertEquals(200, get("/").first)
        }
    }

    @Test fun lifecycleRunsWhileInFrontAndStopsAfterIdleOrClose() {
        var starts = 0
        var stops = 0
        val l = PreviewLifecycle(60_000, start = { starts++; 4000 + starts }, stop = { stops++ })
        assertEquals(4001, l.resume())
        assertEquals(4001, l.resume()) // already running: same port, no second start
        assertEquals(1, starts)
        l.background(now = 1_000)
        assertFalse(l.tick(now = 60_999))
        assertEquals(1_000L, l.remaining(now = 60_000))
        l.resume() // came back in time: the countdown is off
        assertNull(l.remaining(now = 61_000))
        assertFalse(l.tick(now = 200_000))
        l.background(now = 200_000)
        l.background(now = 230_000) // a second stop does not restart the countdown
        assertTrue(l.tick(now = 260_000))
        assertNull(l.port)
        assertEquals(1, stops)
        assertEquals(4002, l.resume()) // back after the stop: starts again
        l.close()
        assertEquals(2, stops)
        assertNull(l.resume()) // a closed window never restarts
        assertNull(l.port)
        assertEquals(2, starts)
    }

    @Test fun aServerThatDiedIsStartedAgainOnResume() {
        var up = false
        var starts = 0
        val l = PreviewLifecycle(60_000, start = { starts++; up = true; 5000 }, stop = { up = false }, alive = { up })
        l.resume()
        up = false // the socket went away on its own
        assertEquals(5000, l.resume())
        assertEquals(2, starts)
        assertTrue(up)
    }

    @Test fun aFailedStartLeavesItStoppedAndCanBeRetried() {
        var fail = true
        val l = PreviewLifecycle(60_000, start = { if (fail) throw java.io.IOException("no port") else 6000 }, stop = {})
        assertTrue(runCatching { l.resume() }.isFailure)
        assertNull(l.port)
        fail = false
        assertEquals(6000, l.resume())
    }

    @Test fun theRealServerFollowsTheLifecycle() {
        val other = PreviewServer(app)
        val l = PreviewLifecycle(1_000, start = { other.start(it) }, stop = { other.stop() }, alive = { other.running })
        val p = l.resume()!!
        assertEquals(p, other.port)
        l.background(now = 0)
        assertTrue(l.tick(now = 1_000))
        assertNull(other.port)
        assertTrue(runCatching { Socket(PreviewServer.HOST, p).close() }.isFailure)
        assertTrue(l.resume() != null) // back: restarts (on the same port when it is free)
        assertTrue(other.running)
        l.close()
        assertFalse(other.running)
    }

    @Test fun previewToolParsesAndOldSavesLoad() {
        val c = OpenRouterBrain.parseToolCall("1", "preview_webapp", """{"path":"apps/timer","title":"Timer"}""") as ToolCall.PreviewWebapp
        assertEquals("apps/timer", c.path)
        assertEquals("Timer", c.title)
        assertTrue(OpenRouterBrain.parseToolCall("2", "preview_webapp", "{}") is ToolCall.Invalid)
        val old = """{"id":"s","title":"t","createdAt":0,"turns":[{"id":"a","role":"ASSISTANT","text":"hi","createdAt":1}]}"""
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString(Session.serializer(), old).turns.single().previews.isEmpty())
    }
}
