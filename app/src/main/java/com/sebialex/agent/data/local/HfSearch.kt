package com.sebialex.agent.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * A `.litertlm` model found on (or added from) Hugging Face, stored as a plain setting so it
 * survives restarts and backups. [sizeBytes] 0 means unknown until the download reports it.
 */
@Serializable
data class HfModel(
    val repo: String,
    val file: String,
    val sizeBytes: Long = 0,
    val addedAt: Long = 0,
) {
    val id: String get() = LocalModels.PREFIX + "hf/" + repo + "/" + file
    val name: String get() = (repo.substringAfter('/') + " " + file.removeSuffix(".litertlm"))
        .replace('_', ' ').replace('-', ' ')

    fun toLocalModel() = LocalModel(
        id = id,
        name = name,
        tagline = repo,
        fileName = file,
        url = "https://huggingface.co/$repo/resolve/main/$file",
        sizeBytes = sizeBytes,
        maxContext = 32_000,
        imported = false,
        pageUrl = "https://huggingface.co/$repo",
    )
}

/** One hit: a repo and the `.litertlm` file in it that this phone could run. */
data class HfHit(val repo: String, val file: String, val sizeBytes: Long, val gated: Boolean, val downloads: Long = 0) {
    val model: HfModel get() = HfModel(repo, file, sizeBytes, System.currentTimeMillis())
}

/**
 * Finds models this phone can run on Hugging Face: repos that ship a `.litertlm` file (the
 * LiteRT-LM format). An empty query lists the most downloaded ones; otherwise the query is
 * ANDed with the LiteRT-LM filter, so a search only ever returns runnable repos. A token is
 * only needed for gated repos.
 */
object HfSearch {
    private val json = Json { ignoreUnknownKeys = true }

    private const val resultsPerPage = 30
    private const val maxPerRepo = 4
    private const val maxHits = 24

    suspend fun search(query: String, token: String): List<HfHit> = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) {
            "https://huggingface.co/api/models?filter=litertlm&sort=downloads&direction=-1&limit=$resultsPerPage&full=true"
        } else {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            "https://huggingface.co/api/models?search=$q&filter=litertlm&limit=$resultsPerPage&full=true"
        }
        val body = get(url, token) ?: throw IOException("Hugging Face did not answer.")
        val repos = json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .filter { repo -> repo["siblings"]?.jsonArray?.any { it.jsonObject["rfilename"]?.jsonPrimitive?.content?.endsWith(".litertlm") == true } == true }
            .map { repo ->
                Triple(
                    repo["id"]?.jsonPrimitive?.content,
                    repo["gated"]?.jsonPrimitive?.content ?: "false",
                    repo["downloads"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                )
            }
            .take(12)
        val hits = ArrayList<HfHit>()
        for ((repo, gated, downloads) in repos) {
            if (repo == null) continue
            val tree = runCatching { get("https://huggingface.co/api/models/$repo/tree/main", token) }.getOrNull() ?: continue
            runCatching {
                json.parseToJsonElement(tree).jsonArray
                    .map { it.jsonObject }
                    .filter { it["path"]?.jsonPrimitive?.content?.endsWith(".litertlm") == true }
                    .take(maxPerRepo)
                    .forEach { entry ->
                        hits += HfHit(
                            repo = repo,
                            file = entry["path"]!!.jsonPrimitive.content,
                            sizeBytes = entry["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                            gated = gated != "false",
                            downloads = downloads,
                        )
                    }
            }
            if (hits.size >= maxHits) return@withContext hits.take(maxHits)
        }
        if (query.isBlank()) hits else hits.sortedBy { it.sizeBytes }
    }

    private fun get(url: String, token: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
