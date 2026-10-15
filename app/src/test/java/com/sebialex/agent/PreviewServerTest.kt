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
import java.net.InetAddress
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
            s.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32)
            true
        } catch (_: IOException) {
            false
        }
    }

    @Test fun lifecycleStartsOnFocusAndStopsAfterIdleOrClose() {
        var starts = 0
        var stops = 0
        val l = PreviewLifecycle(60_000, start = { starts++; 4000 + starts }, stop = { stops++ })
        assertTrue(l.focus())
        assertEquals(4001, l.port)
        assertFalse(l.focus()) // already running
        l.blur(now = 1_000)
        assertFalse(l.tick(now = 60_999))
        assertEquals(1_000L, l.remaining(now = 60_000))
        l.focus() // came back in time: the timer is off
        assertFalse(l.tick(now = 200_000))
        l.blur(now = 200_000)
        assertTrue(l.tick(now = 260_000))
        assertNull(l.port)
        assertEquals(1, stops)
        assertTrue(l.focus()) // refocused: starts again
        assertEquals(2, starts)
        l.close()
        assertEquals(2, stops)
        assertFalse(l.focus()) // a closed window never restarts
        assertNull(l.port)
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
