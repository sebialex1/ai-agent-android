package com.sebialex.agent.data.web

import com.sebialex.agent.domain.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Key-less web access over plain HTTPS.
 *  - fetch: GET the page and reduce HTML to readable text.
 *  - search: DuckDuckGo's HTML results page (https://html.duckduckgo.com/html/).
 */
class DirectWebClient(
    private val searchEndpoint: String = "https://html.duckduckgo.com/html/",
) : WebGateway {

    override suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val html = get(searchEndpoint + "?q=" + URLEncoder.encode(query, "UTF-8"), maxBytes = 1_500_000).body
        val hits = parseDuckDuckGo(html)
        if (hits.isEmpty()) throw IOException("search returned no results")
        hits
    }

    override suspend fun fetch(url: String): FetchedPage = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw IOException("not an http(s) URL: $url")
        val r = get(url, maxBytes = 3_000_000)
        val isHtml = r.contentType.contains("html") || r.body.trimStart().startsWith("<")
        if (!isHtml) return@withContext FetchedPage(r.finalUrl, hostOf(r.finalUrl), r.body.take(MAX_TEXT))
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(r.body)
            ?.groupValues?.get(1)?.let { decodeEntities(it).trim() }?.takeIf { it.isNotEmpty() } ?: hostOf(r.finalUrl)
        FetchedPage(r.finalUrl, title, htmlToText(r.body).take(MAX_TEXT))
    }

    private data class Response(val body: String, val contentType: String, val finalUrl: String)

    private fun get(url: String, maxBytes: Int): Response {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val bytes = conn.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16_384)
                    while (buf.size() < maxBytes) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toByteArray()
                }
                val type = conn.contentType.orEmpty().lowercase()
                val cs = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)
                val text = runCatching { String(bytes, java.nio.charset.Charset.forName(cs ?: "UTF-8")) }.getOrElse { String(bytes, Charsets.UTF_8) }
                return Response(text, type, current)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    companion object {
        private const val MAX_TEXT = 60_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        fun parseDuckDuckGo(html: String): List<SearchHit> {
            val links = Regex("<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").findAll(html).toList()
            val snippets = Regex("<a[^>]+class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").findAll(html).map { it.groupValues[1] }.toList()
            return links.mapIndexedNotNull { i, m ->
                val url = resolveDuckLink(decodeEntities(m.groupValues[1])) ?: return@mapIndexedNotNull null
                if (url.contains("duckduckgo.com/y.js")) return@mapIndexedNotNull null // ads
                SearchHit(
                    title = decodeEntities(stripTags(m.groupValues[2])).trim(),
                    url = url,
                    snippet = snippets.getOrNull(i)?.let { decodeEntities(stripTags(it)).trim() }.orEmpty(),
                )
            }.distinctBy { it.url }.take(8)
        }

        private fun resolveDuckLink(href: String): String? {
            val abs = if (href.startsWith("//")) "https:$href" else href
            val uddg = Regex("[?&]uddg=([^&]+)").find(abs)?.groupValues?.get(1)
            val target = if (uddg != null) URLDecoder.decode(uddg, "UTF-8") else abs
            return target.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }

        private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "")

        fun htmlToText(html: String): String {
            var s = html
            s = s.replace(Regex("<(script|style|noscript|svg|head|nav|footer|form)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
            s = s.replace(Regex("<!--[\\s\\S]*?-->"), " ")
            s = s.replace(Regex("<h([1-6])[^>]*>", RegexOption.IGNORE_CASE)) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " }
            s = s.replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
            s = s.replace(Regex("<(br|/p|/div|/h[1-6]|/li|/tr|/section|/article|/table)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            s = s.replace(Regex("<[^>]+>"), " ")
            s = decodeEntities(s)
            return s.lines().map { it.replace(Regex("[ \\t\\u00a0]+"), " ").trim() }
                .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        }

        fun decodeEntities(s: String): String = s
            .replace(Regex("&#(\\d+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("") }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("") }
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
