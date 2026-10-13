package com.sebialex.agent.domain.tools

import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Network tools beyond search/fetch: raw HTTP requests and file downloads, plus device facts. */
internal object NetTools {
    private const val MAX_BODY_CHARS = 20_000
    private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    private const val UA = "AgentForAndroid/1.0 (+https://github.com/sebialex1/ai-agent-android)"

    private fun checkUrl(url: String): URL {
        val u = runCatching { URL(url.trim()) }.getOrElse { throw IOException("not a valid URL: $url") }
        if (u.protocol != "http" && u.protocol != "https") throw IOException("only http(s) URLs are supported")
        return u
    }

    fun request(call: ToolCall.HttpRequest): ToolResult {
        val conn = checkUrl(call.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = call.method.takeIf { it in setOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH") }
                ?.let { if (it == "PATCH") "POST" else it } ?: "GET"
            if (call.method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            call.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (call.body != null && conn.requestMethod != "GET" && conn.requestMethod != "HEAD") {
                conn.doOutput = true
                if (call.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", if (call.body.trimStart().startsWith("{") || call.body.trimStart().startsWith("[")) "application/json" else "text/plain; charset=utf-8")
                }
                conn.outputStream.use { it.write(call.body.toByteArray()) }
            }
            val code = conn.responseCode
            val headers = conn.headerFields.filterKeys { it != null }.entries
                .filter { (k, _) -> k.lowercase() in setOf("content-type", "content-length", "location", "retry-after", "x-ratelimit-remaining", "etag", "last-modified") }
                .joinToString("\n") { (k, v) -> "$k: ${v.joinToString(", ")}" }
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = if (conn.requestMethod == "HEAD") "" else stream?.bufferedReader()?.use { r ->
                val buf = CharArray(MAX_BODY_CHARS + 1)
                var n = 0
                while (n < buf.size) {
                    val r2 = r.read(buf, n, buf.size - n)
                    if (r2 < 0) break
                    n += r2
                }
                val s = String(buf, 0, minOf(n, MAX_BODY_CHARS))
                if (n > MAX_BODY_CHARS) "$s\n[response cut at $MAX_BODY_CHARS characters]" else s
            }.orEmpty()
            val out = "HTTP $code ${conn.responseMessage.orEmpty()}".trim() + "\n" + headers + "\n\n" + text
            return ToolResult(call.callId, code in 200..399, out.trimEnd())
        } finally {
            conn.disconnect()
        }
    }

    fun download(ws: Workspace, call: ToolCall.DownloadFile): ToolResult {
        val url = checkUrl(call.url)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code while downloading ${call.url}")
            val length = conn.contentLengthLong
            if (length > MAX_DOWNLOAD_BYTES) throw IOException("file is ${length / (1 shl 20)} MB, over the ${MAX_DOWNLOAD_BYTES / (1 shl 20)} MB limit")
            val name = call.path ?: ("downloads/" + fileName(conn, url))
            val target = ws.resolve(name)
            if (target.isDirectory) throw IOException("$name is a folder")
            target.parentFile?.mkdirs()
            var total = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) {
                            out.close(); target.delete()
                            throw IOException("download stopped: over the ${MAX_DOWNLOAD_BYTES / (1 shl 20)} MB limit")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            val path = ws.virtualOf(target)
            val attachment = FileAttachment(path = path, name = target.name, updatedAt = System.currentTimeMillis())
            val type = conn.contentType?.substringBefore(';').orEmpty()
            return ToolResult(call.callId, true, "saved $total bytes to $path" + if (type.isNotEmpty()) " ($type)" else "", attachment = attachment)
        } finally {
            conn.disconnect()
        }
    }

    private fun fileName(conn: HttpURLConnection, url: URL): String {
        val disposition = conn.getHeaderField("Content-Disposition").orEmpty()
        val fromHeader = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
        val fromPath = url.path.substringAfterLast('/').takeIf { it.isNotBlank() }
        val raw = fromHeader ?: fromPath ?: "download"
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "download" }.take(120)
    }

    fun deviceInfo(): String {
        val now = Date()
        val tz = TimeZone.getDefault()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(now)
        val human = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(now)
        return buildString {
            appendLine("now: $iso")
            appendLine("local: $human")
            appendLine("time zone: ${tz.id} (${tz.getDisplayName(tz.inDaylightTime(now), TimeZone.SHORT, Locale.US)})")
            appendLine("unix ms: ${now.time}")
            appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            append("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }
    }
}
