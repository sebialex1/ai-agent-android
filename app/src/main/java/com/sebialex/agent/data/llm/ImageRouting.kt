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
            put("model", requestModel())
            put("prompt", prompt)
            put("n", 1)
            MediaModels.openAiImageFields(provider.baseUrl, model, aspectRatio).forEach { (k, v) -> put(k, v) }
        }.toString()
        // One retry for a 5xx from a gateway: local shims (and the providers behind them) hiccup.
        var retries = 0
        while (true) {
            val (code, text) = post(key, body)
            if (code !in 200..299) {
                if (code in 500..599 && retries++ == 0) {
                    Thread.sleep(2_000)
                    continue
                }
                val detail = MediaModels.errorDetail(text)
                throw IOException(errorText(code, detail.orEmpty()))
            }
            return when (val p = MediaModels.parseImageResponse(text)) {
                is MediaModels.Payload.Bytes -> GeneratedImage(p.bytes, p.mime)
                is MediaModels.Payload.Link -> downloadImage(p.url, p.mime)
            }
        }
    }

    private fun post(key: String?, body: String): Pair<Int, String> {
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
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** The model name exactly as the caller picked it, except where the endpoint cannot serve it. */
    private fun requestModel(): String =
        if (provider.preset == CustomProviders.GEMINI_ACCOUNT_PRESET) modelImageName(model) else model

    /** The failure message, with the one useful diagnosis this side of the protocol knows. */
    private fun errorText(code: Int, detail: String): String {
        // A 5xx from the Gemini account shim is its upstream (gemini.google.com) failing: daily
        // media quota per account, or a session the shim could not refresh. Its `detail` (forwarded
        // by errorDetail) names the concrete reason, so only guess when it said nothing at all.
        val hint =
            if (code in 500..599 && provider.preset == CustomProviders.GEMINI_ACCOUNT_PRESET)
                if (detail.isBlank())
                    " \u2014 the Gemini shim could not generate this image: the account's daily image quota " +
                        "may be spent, or its session needs attention. Try again in a while, or check " +
                        "Settings > Gemini (my account)."
                else
                    " \u2014 the Gemini shim could not serve this image (its reason is above). If it keeps " +
                        "failing, check Settings > Gemini (my account)."
            else ""
        return "HTTP $code" + (if (detail.isNotBlank()) ": $detail" else "") + hint
    }
}

/**
 * The image model name the Gemini account shim can actually serve.
 *
 * Its `/v1/models` advertises chat tier suffixes (`-plus`, `-advanced`) that its media registry has
 * no entry for, and its model resolver passes those names through untouched — so an image request
 * for `gemini-3-pro-plus` lands as an unknown model upstream and returns HTTP 502. Chat tolerates
 * the tier names (its path falls back to a default), image generation does not.
 */
internal fun modelImageName(model: String): String {
    var name = model.trim().substringAfterLast('/')
    var stripped = true
    while (stripped) { // e.g. gemini-3-pro-plus -> gemini-3-pro; gemini-3-flash-thinking-plus keeps -thinking
        stripped = false
        for (tier in listOf("-plus", "-advanced")) {
            if (name.endsWith(tier)) {
                name = name.removeSuffix(tier)
                stripped = true
            }
        }
    }
    return name
}

/**
 * The generator behind generate_image: the provider and model picked in Settings (cloud models in
 * Models > Image generation, or an on-device Stable-Diffusion model in On-device > Image), read on
 * every call. It never falls back to another provider: when the chosen provider was removed, has
 * no key, or no longer lists the model as an image model (nor is it marked as one by hand),
 * the tool fails with a message that says so.
 */
class RoutedImageGenerator(
    private val settings: SecureSettings,
    private val openRouter: (String) -> ImageGenerator = { model -> OpenRouterImages(settings, model = { model }) },
    private val custom: (SecureSettings.CustomProvider, String) -> ImageGenerator = { p, m -> OpenAiCompatibleImages(settings, p, m) },
    /** On-device generators, one per image model id (built where its model files can be found). */
    private val device: (String) -> ImageGenerator,
    /** diffusion-step readout of the running generation (null = none running), for the chat UI. */
    private val onStep: (com.sebialex.agent.domain.tools.ImageGenStep?) -> Unit = {},
) : ImageGenerator {

    override suspend fun generate(prompt: String, aspectRatio: String?): GeneratedImage {
        // A new generation first clears the last one's readout, whatever it was — the chat's
        // step row never shows a stale "step 20/20" from an earlier picture.
        onStep(null)
        try {
            return generateInner(prompt, aspectRatio)
        } finally {
            onStep(null)
        }
    }

    private suspend fun generateInner(prompt: String, aspectRatio: String?): GeneratedImage {
        val ref = settings.mediaImageModel()
        if (ref.provider == MediaModels.PROVIDER_DEVICE) {
            // resolve() validates the id; here only the readable message matters.
            val name = com.sebialex.agent.data.local.ImageModels.displayName(ref.model)
            return try {
                device(ref.model).generate(prompt, aspectRatio)
            } catch (e: IOException) {
                throw IOException("Image generation with On-device \u00b7 $name failed: ${e.message}", e)
            }
        }
        val (name, provider) = resolve(ref, settings.customProviders(), settings.imageOverrides(), settings::keyCount)
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
            manualImage: Set<String> = emptySet(),
            keyCount: (String) -> Int,
        ): Pair<String, SecureSettings.CustomProvider?> {
            if (ref.provider == MediaModels.PROVIDER_DEVICE) {
                com.sebialex.agent.data.local.ImageModels.resolve(ref.model) ?: throw IOException(
                    "The saved on-device image model (${ref.model}) does not exist. Pick one in Settings > On-device > Image. Nothing was generated.",
                )
                return "On-device" to null
            }
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
            if (MediaModels.imageSource(p, ref.model, manualImage) == null) throw IOException(
                "${p.name} no longer lists ${ref.model} as an image model, and it is not marked as one in Settings. Pick another image model in Settings > Models > Image generation. Nothing was generated.",
            )
            if (keyCount(CustomProviders.keyRing(p.id)) == 0 && !CustomProviders.isLocalAddress(p.baseUrl)) throw IOException(
                "The image model is ${p.name} \u00b7 ${ref.model}, but no ${p.name} key is saved. Add one in Settings > Models. Nothing was generated.",
            )
            return p.name to p
        }
    }
}
