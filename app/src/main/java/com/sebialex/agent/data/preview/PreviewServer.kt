package com.sebialex.agent.data.preview

import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A tiny static file server for web app previews: GET and HEAD only, files from [root] and
 * nothing outside it (see [StaticFiles]), bound to 127.0.0.1 so no other device can reach it.
 * Every response is `no-store`, so a reload always shows what the agent just rebuilt. One
 * thread accepts, a small pool answers (a page pulls its scripts, styles and images at once).
 * It is started and stopped by the preview window ([PreviewLifecycle]); [start] may be called
 * again after [stop].
 */
class PreviewServer(private val root: File) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** The port while running, else null. */
    val port: Int? get() = socket?.takeIf { !it.isClosed }?.localPort

    val running: Boolean get() = port != null

    /**
     * Starts listening on 127.0.0.1, on [preferredPort] when it is free (so a page reopened
     * after a pause keeps its address and its local storage), else on any free port. Returns the port.
     */
    @Synchronized
    fun start(preferredPort: Int = 0): Int {
        port?.let { return it }
        val server = ServerSocket()
        server.reuseAddress = true
        val loopback = InetAddress.getLoopbackAddress()
        try {
            server.bind(InetSocketAddress(loopback, preferredPort), 32)
        } catch (e: java.io.IOException) {
            if (preferredPort == 0) { server.close(); throw e }
            server.bind(InetSocketAddress(loopback, 0), 32)
        }
        val workers = Executors.newFixedThreadPool(4) { r -> Thread(r, "preview-http").apply { isDaemon = true } }
        socket = server
        pool = workers
        Thread({ acceptLoop(server, workers) }, "preview-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        pool?.shutdownNow()
        socket = null
        pool = null
    }

    private fun acceptLoop(server: ServerSocket, workers: ExecutorService) {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: SocketException) { break } catch (_: java.io.IOException) { continue }
            try {
                workers.execute { client.use { serve(it) } }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        // Headers are read and ignored: no keep-alive, no ranges, no conditional requests.
        while (true) { val h = readLine(input) ?: return; if (h.isEmpty()) break }
        val out = client.getOutputStream()
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(out, 400, "Bad Request", head = false)
        val method = parts[0]
        val head = method == "HEAD"
        if (method != "GET" && !head) return respond(out, 405, "Method Not Allowed", head = false, extra = "Allow: GET, HEAD\r\n")
        val target = parts[1]
        if (!target.startsWith("/")) return respond(out, 400, "Bad Request", head)
        when (val r = StaticFiles.resolve(root, target)) {
            is StaticFiles.Resolution.Found -> {
                val file = r.file
                val length = file.length()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: ${StaticFiles.mimeOf(file.name)}\r\nContent-Length: $length\r\n" +
                        COMMON_HEADERS + "\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                if (!head) file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.flush()
            }
            is StaticFiles.Resolution.Redirect -> respond(out, 301, "Moved Permanently", head, extra = "Location: ${r.location}\r\n")
            StaticFiles.Resolution.NotFound -> respond(out, 404, "Not Found", head)
            StaticFiles.Resolution.Forbidden -> respond(out, 403, "Forbidden", head)
        }
    }

    private fun respond(out: OutputStream, code: Int, reason: String, head: Boolean, extra: String = "") {
        val body = "$code $reason\n".toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                extra + COMMON_HEADERS + "\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        if (!head) out.write(body)
        out.flush()
    }

    /** One CRLF (or LF) terminated header line, at most 8 KB; null at end of stream or when too long. */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length >= 8192) return null
            sb.append(b.toChar())
        }
    }

    private companion object {
        const val COMMON_HEADERS = "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n"
    }
}
