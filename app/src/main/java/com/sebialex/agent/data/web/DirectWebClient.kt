package com.sebialex.agent.data.web

import com.sebialex.agent.domain.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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

    override suspend fun search(query: String, withImages: Boolean): List<SearchHit> = withContext(Dispatchers.IO) {
        val html = get(searchEndpoint + "?q=" + URLEncoder.encode(query, "UTF-8"), maxBytes = 1_500_000).body
        val hits = parseDuckDuckGo(html)
        if (!withImages) {
            if (hits.isEmpty()) throw IOException("search returned no results")
            return@withContext hits
        }
        // Image results are best effort: when DuckDuckGo's images endpoint does not answer,
        // the web results are still returned instead of failing the whole search.
        val images = runCatching { imageResults(query) }.getOrDefault(emptyList())
        val combined = hits + images
        if (combined.isEmpty()) throw IOException("search returned no results")
        combined
    }

    override suspend fun fetch(url: String): FetchedPage = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw IOException("not an http(s) URL: $url")
        val r = get(url, maxBytes = 3_000_000)
        // A directly fetched image URL would arrive as binary noise: tell the model how to look at it.
        if (r.contentType.startsWith("image/")) return@withContext FetchedPage(
            r.finalUrl, hostOf(r.finalUrl),
            imageMessage(r.finalUrl, r.contentType, r.body.toByteArray().size),
        )
        val isHtml = r.contentType.contains("html") || r.body.trimStart().startsWith("<")
        if (!isHtml) return@withContext FetchedPage(r.finalUrl, hostOf(r.finalUrl), r.body.take(MAX_TEXT))
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(r.body)
            ?.groupValues?.get(1)?.let { decodeEntities(it).trim() }?.takeIf { it.isNotEmpty() } ?: hostOf(r.finalUrl)
        val text = htmlToText(r.body).take(MAX_TEXT)
        // Pages that build themselves with JavaScript arrive almost empty; say so instead of
        // letting the model present the fragment as the whole page.
        val note = if (text.length < 300 && r.body.length > 5_000 && Regex("<script", RegexOption.IGNORE_CASE).containsMatchIn(r.body))
            "\n\n(This page builds its content with JavaScript, which web_fetch does not run; the text above may be only a fragment of it.)"
        else ""
        FetchedPage(r.finalUrl, title, text + note, extractImages(r.body, r.finalUrl))
    }

    private data class Response(val body: String, val contentType: String, val finalUrl: String)

    /**
     * DuckDuckGo's image search: the query page hands out a `vqd` token, `i.js` then answers a
     * JSON array of `{image, width, height, title, url}` ("url" is the page the image came from).
     */
    private fun imageResults(query: String): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val page = get("https://duckduckgo.com/?q=$q&iax=images&ia=images", maxBytes = 1_500_000).body
        val vqd = Regex("vqd=[\"']?(-?[0-9][0-9-]*)").find(page)?.groupValues?.get(1)
            ?: throw IOException("could not start an image search")
        val body = get(
            "https://duckduckgo.com/i.js?l=us-en&o=json&q=$q&vqd=" + URLEncoder.encode(vqd, "UTF-8") + "&f=,,,&p=1",
            maxBytes = 1_500_000,
            headers = mapOf("Referer" to "https://duckduckgo.com/", "Accept" to "application/json"),
        ).body
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val items = runCatching { json.parseToJsonElement(body).jsonArray }.getOrElse { return emptyList() }
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val image = (o["image"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (!image.startsWith("http")) return@mapNotNull null
            val w = (o["width"] as? JsonPrimitive)?.content
            val h = (o["height"] as? JsonPrimitive)?.content
            SearchHit(
                title = (o["title"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "image result" },
                url = image,
                snippet = "image result" +
                    (if (w != null && h != null) " ${w}x$h" else "") +
                    " from " + ((o["url"] as? JsonPrimitive)?.content ?: "the page it came from"),
            )
        }.distinctBy { it.url }.take(10)
    }

    private fun get(url: String, maxBytes: Int, headers: Map<String, String> = emptyMap()): Response {
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
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
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

        /** The message shown when web_fetch is pointed at an image URL directly. */
        fun imageMessage(url: String, contentType: String, bytes: Int): String {
            val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "image" }
            return "This URL is an image, not a page: $name ($contentType, ${bytes / 1024} KB). " +
                "To see it: download_file this URL into the workspace, then view_image the saved file."
        }

        /**
         * Image URLs referenced by an HTML page, in document order: <img src>, the first
         * candidates of srcset, and og:image / twitter:image meta tags. Resolved against the
         * page URL, http(s) only, deduped, at most [MAX_PAGE_IMAGES].
         */
        fun extractImages(html: String, baseUrl: String): List<String> {
            val raw = buildList {
                for (m in Regex("<img\\b[^>]*?>", RegexOption.IGNORE_CASE).findAll(html)) {
                    val tag = m.value
                    add(Regex("\\bsrc=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1))
                    add(Regex("\\bsrc='([^']+)'", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1))
                    val set = Regex("\\bsrcset=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                    add(set?.split(',')?.firstOrNull()?.trim()?.substringBefore(' '))
                }
                addAll(
                    Regex("<(?:meta|source)\\b[^>]*?(?:property|name)=\"(?:og:image|og:image:secure_url|twitter:image|twitter:image:src)\"[^>]*?\\bcontent=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                        .findAll(html).map { it.groupValues[1] },
                )
            }
            return raw.mapNotNull {
                val cleaned = it?.trim().orEmpty()
                if (cleaned.isEmpty() || cleaned.startsWith("data:")) return@mapNotNull null
                runCatching { java.net.URL(java.net.URL(baseUrl), cleaned).toString() }.getOrNull()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }.distinct().take(MAX_PAGE_IMAGES)
        }

        private const val MAX_PAGE_IMAGES = 12

        fun decodeEntities(s: String): String = s
            .replace(Regex("&#(\\d+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("") }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault("") }
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
