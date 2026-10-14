package com.sebialex.agent.data.llm

import com.sebialex.agent.data.secure.SecureSettings
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
 * generate_image through an OpenAI-compatible `POST <root>/images/generations` of a custom
 * provider (OpenAI, Gemini's compatibility layer, xAI, Together, ...), with that provider's key
 * ring. A local server without keys runs without one.
 */
class OpenAiCompatibleImages(
    private val keys: KeyPool,
    private val provider: SecureSettings.CustomProvider,
    private val model: String,
) : ImageGenerator {
    private val ring = CustomProviders.keyRing(provider.id)

    override suspend fun generate(prompt: String, aspectRatio: String?): GeneratedImage = withContext(Dispatchers.IO) {
        var key = keys.activeKey(ring)
        if (key == null) {
            if (!CustomProviders.isLocalAddress(provider.baseUrl)) {
                throw IOException("no API key for ${provider.name}: add one in Settings > Models")
            }
            return@withContext request(null, prompt, aspectRatio)
        }
        val tried = HashSet<String>()
        while (true) {
            tried += key!!
            try {
                return@withContext request(key, prompt, aspectRatio).also { keys.markKeyOk(ring, key!!) }
            } catch (e: IOException) {
                if (!KeyPool.isKeyFailure(e.message)) throw e
                val next = keys.rotateKey(ring, key!!, e.message.orEmpty())
                if (next == null || next in tried) throw e
                key = next
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }

    private fun request(key: String?, prompt: String, aspectRatio: String?): GeneratedImage {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            MediaModels.openAiImageFields(provider.baseUrl, model, aspectRatio).forEach { (k, v) -> put(k, v) }
        }.toString()
        val conn = URL(provider.baseUrl.trimEnd('/') + "/images/generations").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            if (key != null) conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = MediaModels.errorDetail(text)
                throw IOException("HTTP $code" + if (!detail.isNullOrBlank()) ": $detail" else "")
            }
            return when (val p = MediaModels.parseImageResponse(text)) {
                is MediaModels.Payload.Bytes -> GeneratedImage(p.bytes, p.mime)
                is MediaModels.Payload.Link -> downloadImage(p.url, p.mime)
            }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * The generator behind generate_image: the provider and model picked in Settings > Models >
 * Image generation, read on every call. It never falls back to another provider: when the chosen
 * provider was removed, has no key, or no longer lists the model as an image model, the tool
 * fails with a message that says so.
 */
class RoutedImageGenerator(
    private val settings: SecureSettings,
    private val openRouter: (String) -> ImageGenerator = { model -> OpenRouterImages(settings, model = { model }) },
    private val custom: (SecureSettings.CustomProvider, String) -> ImageGenerator = { p, m -> OpenAiCompatibleImages(settings, p, m) },
) : ImageGenerator {

    override suspend fun generate(prompt: String, aspectRatio: String?): GeneratedImage {
        val ref = settings.mediaImageModel()
        val (name, provider) = resolve(ref, settings.customProviders(), settings::keyCount)
        return try {
            if (provider == null) openRouter(ref.model).generate(prompt, aspectRatio)
            else custom(provider, ref.model).generate(prompt, aspectRatio)
        } catch (e: IOException) {
            throw IOException("Image generation with $name \u00b7 ${ref.model} failed: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Checks [ref] against the current providers: (provider name, custom provider or null for
         * OpenRouter). Throws a readable error instead of switching to another provider.
         */
        fun resolve(
            ref: MediaModelRef,
            providers: List<SecureSettings.CustomProvider>,
            keyCount: (String) -> Int,
        ): Pair<String, SecureSettings.CustomProvider?> {
            if (ref.provider == MediaModels.PROVIDER_OPENROUTER) {
                if (keyCount(KeyPool.OPENROUTER) == 0) throw IOException(
                    "The image model is OpenRouter \u00b7 ${ref.model}, but no OpenRouter key is saved. " +
                        "Add one in Settings > Models, or pick another image model there. Nothing was generated.",
                )
                return "OpenRouter" to null
            }
            val id = MediaModels.customProviderId(ref) ?: throw IOException(
                "The saved image provider \"${ref.provider}\" is not one this app knows. Pick an image model in Settings > Models. Nothing was generated.",
            )
            val p = providers.firstOrNull { it.id == id } ?: throw IOException(
                "The image model's provider was removed in Settings. Pick another image model in Settings > Models > Image generation. Nothing was generated.",
            )
            if (MediaModels.imageSource(p, ref.model) == null) throw IOException(
                "${p.name} no longer lists ${ref.model} as an image model. Pick another image model in Settings > Models > Image generation. Nothing was generated.",
            )
            if (keyCount(CustomProviders.keyRing(p.id)) == 0 && !CustomProviders.isLocalAddress(p.baseUrl)) throw IOException(
                "The image model is ${p.name} \u00b7 ${ref.model}, but no ${p.name} key is saved. Add one in Settings > Models. Nothing was generated.",
            )
            return p.name to p
        }
    }
}
