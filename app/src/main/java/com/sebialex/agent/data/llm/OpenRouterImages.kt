package com.sebialex.agent.data.llm

import com.sebialex.agent.domain.agent.KeyPool
import com.sebialex.agent.domain.tools.GeneratedImage
import com.sebialex.agent.domain.tools.ImageGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * generate_image through OpenRouter's Image API (`POST https://openrouter.ai/api/v1/images`,
 * body `{model, prompt, aspect_ratio?}`, answer `data[].b64_json` with `media_type`), using
 * the OpenRouter key ring (a failing key rotates like chat requests do).
 */
class OpenRouterImages(
    private val keys: KeyPool,
    /** Chosen in Settings > Models > Image generation; [DEFAULT_MODEL] until the user picks one. */
    private val model: () -> String = { DEFAULT_MODEL },
) : ImageGenerator {
    override suspend fun generate(prompt: String, aspectRatio: String?): GeneratedImage = withContext(Dispatchers.IO) {
        var key = keys.activeKey(KeyPool.OPENROUTER) ?: throw IOException("no OpenRouter key: add one in Settings > Models")
        val tried = HashSet<String>()
        while (true) {
            tried += key
            try {
                return@withContext request(key, prompt, aspectRatio).also { keys.markKeyOk(KeyPool.OPENROUTER, key) }
            } catch (e: IOException) {
                if (!KeyPool.isKeyFailure(e.message)) throw e
                val next = keys.rotateKey(KeyPool.OPENROUTER, key, e.message.orEmpty())
                if (next == null || next in tried) throw e
                key = next
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }

    private fun request(key: String, prompt: String, aspectRatio: String?): GeneratedImage {
        val body = buildJsonObject {
            put("model", model())
            put("prompt", prompt)
            aspectRatio?.trim()?.takeIf { it.matches(Regex("^(auto|\\d{1,2}:\\d{1,2})$")) }?.let { put("aspect_ratio", it) }
        }.toString()
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("HTTP-Referer", "https://github.com/sebialex1/ai-agent-android")
            conn.setRequestProperty("X-Title", "Agent for Android")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = MediaModels.errorDetail(text)
                throw IOException("HTTP $code" + if (!detail.isNullOrBlank()) ": $detail" else "")
            }
            // Some models answer with a URL (or a data URL) instead of inline bytes.
            return when (val p = MediaModels.parseImageResponse(text)) {
                is MediaModels.Payload.Bytes -> GeneratedImage(p.bytes, p.mime)
                is MediaModels.Payload.Link -> downloadImage(p.url, p.mime)
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/images"
        const val DEFAULT_MODEL = "google/gemini-2.5-flash-image"
    }
}

/** Downloads an image that a model returned as a link. */
internal fun downloadImage(url: String, mime: String): GeneratedImage {
    val img = URL(url).openConnection() as HttpURLConnection
    try {
        img.connectTimeout = 20_000
        img.readTimeout = 60_000
        if (img.responseCode !in 200..299) throw IOException("could not download the generated image (HTTP ${img.responseCode})")
        return GeneratedImage(img.inputStream.use { it.readBytes() }, img.contentType ?: mime)
    } finally {
        img.disconnect()
    }
}
