package com.sebialex.agent.data.web

import com.sebialex.agent.domain.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP client for the TinyFish Search and Fetch APIs.
 *  - GET  https://api.search.tinyfish.ai?query=...
 *  - POST https://api.fetch.tinyfish.ai {"urls": [...], "format": "markdown"}
 * Authenticated with the `X-API-Key` header.
 */
class TinyFishClient(
    private val apiKey: String,
    private val searchEndpoint: String = "https://api.search.tinyfish.ai",
    private val fetchEndpoint: String = "https://api.fetch.tinyfish.ai",
) : WebGateway {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val url = URL(searchEndpoint + "?query=" + URLEncoder.encode(query, "UTF-8"))
        val body = request(url, "GET", null)
        val root = json.parseToJsonElement(body).jsonObject
        val results = (root["results"] as? JsonArray) ?: JsonArray(emptyList())
        results.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val link = o.str("url") ?: o.str("link") ?: return@mapNotNull null
            SearchHit(title = o.str("title") ?: hostOf(link), url = link, snippet = o.str("snippet") ?: o.str("description") ?: "")
        }
    }

    override suspend fun fetch(url: String): FetchedPage = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("urls") { add(url) }
            put("format", "markdown")
        }.toString()
        val body = request(URL(fetchEndpoint), "POST", payload)
        val root = json.parseToJsonElement(body).jsonObject
        val first = root["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IOException("fetch failed: ${root["errors"] ?: "no result"}")
        FetchedPage(url = first.str("url") ?: url, title = first.str("title") ?: hostOf(url), text = first.str("text") ?: "")
    }

    private fun request(url: URL, method: String, body: String?): String {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("TinyFish HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
