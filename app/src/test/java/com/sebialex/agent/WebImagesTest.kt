package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.data.web.DirectWebClient
import com.sebialex.agent.data.web.FetchedPage
import com.sebialex.agent.data.shell.Shell
import com.sebialex.agent.data.shell.ShellResult
import com.sebialex.agent.data.web.WebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** web images: web_fetch lists a page's images and handles direct image URLs; web_search finds images. */
class WebImagesTest {

    @Test fun extractImagesFindsImgAndMetaAbsoluteResolved() {
        val html = """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/og.png">
            </head><body>
              <img src="/photos/main.jpg" alt="a">
              <img src='second.png'>
              <img src="data:image/gif;base64,AAAA" alt="inline">
              <img src="//static.example.org/pixel.png">
            </body></html>
        """.trimIndent()
        val images = DirectWebClient.extractImages(html, "https://example.com/blog/post")
        // img tags first (document order), then meta tags; data: URIs dropped.
        assertEquals(
            listOf(
                "https://example.com/photos/main.jpg",
                "https://example.com/blog/second.png",
                "https://static.example.org/pixel.png",
                "https://cdn.example.com/og.png",
            ),
            images,
        )
    }

    @Test fun extractImagesTakesTheFirstSrcsetCandidateAndCapsTheList() {
        val img = """<img src="a.jpg" srcset="b.jpg 640w, c.jpg 1280w">"""
        val many = (1..15).joinToString("") { """<img src="$it.jpg">""" }
        val images = DirectWebClient.extractImages(img + many, "https://example.com/")
        // src is used as the first candidate; the srcset has its own first candidate too.
        assertTrue("https://example.com/a.jpg" in images && "https://example.com/b.jpg" in images)
        assertEquals(12, images.size)
    }

    @Test fun aDirectImageFetchExplainsHowToLookAtIt() {
        val m = DirectWebClient.imageMessage("https://x.io/pic/photo.jpg?size=big", "image/jpeg", 120_000)
        assertTrue("photo.jpg" in m)
        assertTrue("image/jpeg" in m)
        assertTrue("download_file" in m && "view_image" in m)
    }

    @Test fun webSearchToolRoundTripsTheImagesFlag() {
        val on = OpenRouterBrain.parseToolCall("c1", "web_search", """{"query":"cats","images":true}""")
        assertTrue(on is ToolCall.WebSearch && on.images)
        val off = OpenRouterBrain.parseToolCall("c2", "web_search", """{"query":"cats"}""")
        assertTrue(off is ToolCall.WebSearch && !off.images)
        assertTrue("\"images\":true" in OpenRouterBrain.arguments(ToolCall.WebSearch("c3", "cats", images = true)))
        assertFalse("\"images\"" in OpenRouterBrain.arguments(ToolCall.WebSearch("c4", "cats")))
    }

    private class RecordingWeb(private val images: List<String> = emptyList()) : WebGateway {
        var askedImages = false
        override suspend fun search(query: String, withImages: Boolean): List<SearchHit> {
            askedImages = withImages
            return listOf(SearchHit("Cat page", "https://cats.example", "about cats"))
        }

        override suspend fun fetch(url: String) = FetchedPage(url, "Gallery", "Gallery of cats", images)
    }

    private fun executor(web: WebGateway) = ToolExecutor(
        workspace = Workspace(Files.createTempDirectory("wsimg").toFile()),
        shell = object : Shell {
            override fun execute(command: String, cwd: String) = ShellResult("", 0, cwd)
        },
        web = web,
    )

    @Test fun fetchResultListsThePageImagesWithADownloadHint() = runBlocking {
        val web = RecordingWeb(listOf("https://g.example/1.jpg", "https://g.example/2.png"))
        val result = executor(web).execute(ToolCall.WebFetch("c1", "https://g.example"))
        assertTrue(result.ok)
        assertTrue("Images on this page" in result.output)
        assertTrue("- https://g.example/1.jpg" in result.output)
        assertTrue("- https://g.example/2.png" in result.output)
        assertTrue("download_file" in result.output && "view_image" in result.output)
    }

    @Test fun aPageWithoutImagesAddsNoImageBlock() = runBlocking {
        val web = RecordingWeb()
        val result = executor(web).execute(ToolCall.WebFetch("c2", "https://plain.example"))
        assertFalse("Images on this page" in result.output)
    }

    @Test fun searchPassesTheImagesFlagToTheGateway() = runBlocking {
        val web = RecordingWeb()
        executor(web).execute(ToolCall.WebSearch("c3", "cats", images = true))
        assertTrue(web.askedImages)
        val web2 = RecordingWeb()
        val plain = executor(web2).execute(ToolCall.WebSearch("c4", "dogs"))
        assertFalse(web2.askedImages)
        assertTrue("Cat page" in plain.output)
    }

    /** Serves 3 raw bytes as image/png once, then closes (the request is drained first). */
    private fun servePng(): java.net.ServerSocket {
        val server = java.net.ServerSocket(0)
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                server.accept().use { conn ->
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.getInputStream(), Charsets.UTF_8))
                    var line = reader.readLine()
                    var len = 0
                    while (!line.isNullOrEmpty()) {
                        if (line.lowercase().startsWith("content-length:")) len = line.substringAfter(':').trim().toInt()
                        line = reader.readLine()
                    }
                    repeat(len) { reader.read() }
                    conn.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 3\r\nConnection: close\r\n\r\n")
                            .toByteArray() + byteArrayOf(1, 2, 3),
                    )
                }
            }
        }
        return server
    }

    private fun noShell() = object : Shell {
        override fun execute(command: String, cwd: String) = ShellResult("", 0, cwd)
    }

    @Test fun attachFilesTakesAWebUrlStraightFromSearchResults() = runBlocking {
        val server = servePng()
        try {
            val ws = Workspace(Files.createTempDirectory("wsurl").toFile())
            val result = ToolExecutor(workspace = ws, shell = noShell(), web = RecordingWeb())
                .execute(ToolCall.AttachFiles("c5", listOf(ToolCall.AttachSpec("http://localhost:${server.localPort}/cat.png", "Cat pic"))))
            assertTrue(result.output, result.ok)
            val a = result.artifacts.single()
            assertEquals("Cat pic.png", a.name)
            // The web file was saved into the workspace the chip points at.
            assertTrue(1.toByte() == ws.resolve(a.path).readBytes()[0])
        } finally {
            server.close()
        }
    }

    @Test fun anUnreachableUrlFailsTheAttachWithoutKillingTheRest() = runBlocking {
        val dead = java.net.ServerSocket(0)
        val port = dead.localPort
        dead.close()
        val ws = Workspace(Files.createTempDirectory("wsurl2").toFile())
        ws.write("welcome.txt", "hello there")
        val result = ToolExecutor(workspace = ws, shell = noShell(), web = RecordingWeb()).execute(
            ToolCall.AttachFiles(
                "c6",
                listOf(ToolCall.AttachSpec("http://localhost:$port/nope.png"), ToolCall.AttachSpec("welcome.txt")),
            ),
        )
        // The dead URL is reported, the real file is still attached.
        assertTrue("could not download" in result.output)
        assertTrue(result.artifacts.single().path.endsWith("welcome.txt"))
    }
}
