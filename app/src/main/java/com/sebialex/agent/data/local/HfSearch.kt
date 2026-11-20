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
 * One hit of the image-model search: a `text-to-image` repo and the ncnn-converted file set in
 * it, mapped onto the canonical names the vendored engine loads ([ImageModel] [parts]).
 * [missing] lists what is not there: a non-empty list means the engine cannot run this repo
 * (sadly most HF image models ship plain diffusers weights that no on-device engine ingests).
 */
data class HfImageHit(
    val repo: String,
    val downloads: Long = 0,
    val gated: Boolean = false,
    val sizeBytes: Long = 0,
    /** The canonical files found (engine directory names), empty when no usable set is there. */
    val parts: List<ImageModelPart> = emptyList(),
    /** Readable missing-file kinds when [parts] does not cover the whole set. */
    val missing: List<String> = emptyList(),
) {
    val compatible: Boolean get() = parts.size == 8 && missing.isEmpty()

    val id: String get() = ImageModels.PREFIX + "hf/" + repo

    val name: String get() = (repo.substringAfter('/')).replace('_', ' ').replace('-', ' ')

    fun toImageModel(): ImageModel = ImageModel(
        id = id,
        name = name,
        tagline = "ncnn-converted Stable Diffusion from Hugging Face \u00b7 $repo",
        slug = repo.replace('/', '_').lowercase(),
        zipName = repo.substringAfter('/').lowercase() + ".zip", // unused: parts download one file at a time
        sizeBytes = sizeBytes,
        minRamGb = 8,
        pageUrl = "https://huggingface.co/$repo",
        engine = ImageEngine.NCNN,
        parts = parts,
    )
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

    private const val maxImageRepos = 8

    /**
     * Finds image models this phone can run on Hugging Face: `text-to-image` repos, each checked
     * against the ncnn-converted file set the vendored engine loads. A hit without the full set
     * still comes back (with [HfImageHit.missing] filled) so the list explains itself.
     */
    suspend fun searchImages(query: String, token: String): List<HfImageHit> = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) {
            "https://huggingface.co/api/models?filter=text-to-image&sort=downloads&direction=-1&limit=$resultsPerPage&full=true"
        } else {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            "https://huggingface.co/api/models?search=$q&filter=text-to-image&sort=downloads&direction=-1&limit=$resultsPerPage&full=true"
        }
        val body = get(url, token) ?: throw IOException("Hugging Face did not answer.")
        val repos = json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .mapNotNull { repo ->
                val id = repo["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Triple(
                    id,
                    repo["gated"]?.jsonPrimitive?.content ?: "false",
                    repo["downloads"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                )
            }
        val hits = ArrayList<HfImageHit>()
        for ((repo, gated, downloads) in repos) {
            if (hits.size >= maxImageRepos) break
            val tree = runCatching { get("https://huggingface.co/api/models/$repo/tree/main?recursive=true", token) }.getOrNull() ?: continue
            val entries = runCatching {
                json.parseToJsonElement(tree).jsonArray.mapNotNull { e ->
                    val o = e.jsonObject
                    val path = o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    // LFS weights carry their real size one level down; the raw `size` is the pointer.
                    val size = o["lfs"]?.jsonObject?.get("size")?.jsonPrimitive?.content?.toLongOrNull()
                        ?: o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    path to size
                }
            }.getOrNull() ?: continue
            val (picked, missing) = ncnnFiles(entries)
            if (entries.isEmpty()) continue
            hits += HfImageHit(
                repo = repo,
                downloads = downloads,
                gated = gated != "false",
                sizeBytes = picked.sumOf { it.third },
                parts = picked.map { ImageModelPart(it.first, "https://huggingface.co/$repo/resolve/main/${it.second}", it.third) },
                missing = missing,
            )
        }
        hits.sortedWith(compareByDescending<HfImageHit> { it.compatible }.thenByDescending { it.downloads })
    }

    /** Canonical engine directory names for the vendored engine (see sdncnn_jni.cpp). */
    private val ncnnCanon = listOf("unet.param", "unet.bin", "clip.param", "clip.bin", "vae.param", "vae.bin", "log_sigmas.bin", "vocab.txt")

    /**
     * Maps one repo's file tree onto the canonical ncnn file set: (canonical name, tree path,
     * size) picks plus a readable list of what is missing. Conversion-variant params exist in
     * several resolutions; this engine draws 256x256 (32x32 latent), so 256-256 variants win
     * and 512-only repos count as missing their weights.
     */
    internal fun ncnnFiles(entries: List<Pair<String, Long>>): Pair<List<Triple<String, String, Long>>, List<String>> {
        val missing = ArrayList<String>()
        val picked = ArrayList<Triple<String, String, Long>>()

        fun candidates(kind: String, ext: String) = entries.filter {
            val n = it.first.substringAfterLast('/').lowercase()
            n.endsWith(ext) && (n.contains(kind) || n.startsWith(kind) || n.startsWith(kind.removeSuffix("model")))
        }

        fun pick(kind: String, ext: String): Pair<String, Long>? {
            val all = candidates(kind, ext)
            if (all.isEmpty()) return null
            // The engine draws 256x256: only a 256-256 (or unmarked) variant can run; a set
            // that turned out 512-512-only cannot.
            return all.sortedByDescending { it.first.lowercase().contains("256-256") }
                .firstOrNull { !it.first.lowercase().contains("512-512") }
        }

        for (base in listOf("unet", "clip", "vae")) {
            val recognizable = if (base == "unet") "unetmodel" else if (base == "clip") "frozenclipembedder" else "autoencoderkl"
            val param = pick(recognizable, ".param")
            val bin = pick(recognizable, ".bin")
            if (param == null) missing.add(base + ".param")
            if (bin == null) missing.add(base + ".bin")
            if (param != null && bin != null) {
                picked.add(Triple(base + ".param", param.first, param.second))
                picked.add(Triple(base + ".bin", bin.first, bin.second))
            }
        }
        for (file in listOf("log_sigmas.bin", "vocab.txt")) {
            val entry = entries.firstOrNull { it.first.substringAfterLast('/') == file }
            if (entry == null) missing.add(file) else picked.add(Triple(file, entry.first, entry.second))
        }
        return Pair(picked, missing)
    }

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
