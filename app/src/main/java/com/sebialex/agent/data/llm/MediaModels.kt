package com.sebialex.agent.data.llm

import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.agent.KeyPool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * The model that generate_image uses: which provider serves it plus the model id there, so the
 * same id on two providers never collides. [provider] is [MediaModels.PROVIDER_OPENROUTER] or
 * `custom:<provider id>` (the provider's key ring id).
 */
data class MediaModelRef(val provider: String, val model: String)

/** One image model the user can pick, with the provider it runs on. */
data class MediaModelOption(
    val ref: MediaModelRef,
    val providerName: String,
    /** Display name (the provider's own name when it publishes one, else the id). */
    val name: String,
    /** Why the model is listed: the provider's metadata or a documented model-family rule. */
    val source: String,
)

/** How the image request is sent. */
enum class ImageApi {
    /** OpenRouter's Image API, `POST /api/v1/images` (`aspect_ratio`, answer `data[].b64_json`). */
    OPENROUTER_IMAGES,

    /** OpenAI's Images API, `POST <root>/images/generations` (OpenAI, Gemini, xAI, Together, ...). */
    OPENAI_IMAGES,
}

/**
 * Which providers can generate images, and which of their models. Only real, documented
 * capabilities are listed:
 * - OpenRouter: every model of `GET /api/v1/images/models` (the Image API's own catalog);
 * - custom OpenAI-compatible providers: models their `/models` list marks as image output
 *   (`type: "image"`, `output_modalities: ["image"]`), or, for providers whose list has no such
 *   field, the image model families their docs name for `/images/generations`
 *   (OpenAI `gpt-image-*` / `dall-e-*`, Gemini `gemini-*-image*`, xAI `grok-*-image*`);
 * - Ollama Cloud, Anthropic and on-device models: none (see [LIMITATIONS]).
 */
object MediaModels {
    const val PROVIDER_OPENROUTER = KeyPool.OPENROUTER
    const val IMAGES_ENDPOINT_OPENROUTER = "https://openrouter.ai/api/v1/images/models"

    /** What generate_image used before the picker existed; shown as the default until the user picks. */
    val DEFAULT = MediaModelRef(PROVIDER_OPENROUTER, OpenRouterImages.DEFAULT_MODEL)

    /** Providers this app talks to that cannot generate images here, with the reason shown in Settings. */
    val LIMITATIONS = listOf(
        "Ollama Cloud" to "its image models run only in the local Ollama app, not through ollama.com",
        "Anthropic" to "Claude models do not generate images",
        "On-device" to "the LiteRT-LM models are text models",
    )

    fun customRef(providerId: String, model: String) = MediaModelRef(KeyPool.CUSTOM_PREFIX + providerId, model)

    /** The custom provider id of [ref], or null for OpenRouter. */
    fun customProviderId(ref: MediaModelRef): String? =
        ref.provider.takeIf { it.startsWith(KeyPool.CUSTOM_PREFIX) }?.removePrefix(KeyPool.CUSTOM_PREFIX)?.takeIf { it.isNotEmpty() }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** OpenRouter's `GET /api/v1/images/models` answer: entries whose output modalities include image. */
    fun parseOpenRouterImageModels(body: String): List<MediaModelOption> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val outputs = ((o["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
            // The Image API catalog lists image models only; an explicit text-only entry is still skipped.
            if (outputs != null && "image" !in outputs) return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id
            MediaModelOption(MediaModelRef(PROVIDER_OPENROUTER, id), "OpenRouter", name, "OpenRouter Image API catalog")
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Why [model] of [provider] can generate images, or null when it cannot (or nothing says so).
     * Metadata from the provider's own list wins over the documented family rules.
     */
    fun imageSource(provider: SecureSettings.CustomProvider, model: String): String? {
        provider.modelInfo[model]?.image?.let { return if (it) "${provider.name} model list" else null }
        val id = model.lowercase()
        return when {
            ProviderMetadata.isOpenAiHost(provider.baseUrl) && (id.startsWith("gpt-image-") || id.startsWith("dall-e-")) ->
                "OpenAI Images API model"
            ProviderMetadata.isGeminiHost(provider.baseUrl) && id.startsWith("gemini-") && "-image" in id ->
                "Gemini OpenAI-compatible images.generate model"
            ProviderMetadata.isXaiHost(provider.baseUrl) && id.startsWith("grok-") && "-image" in id ->
                "xAI image generation model"
            else -> null
        }
    }

    /** The image models of one custom provider (from its saved model list). */
    fun customImageModels(provider: SecureSettings.CustomProvider): List<MediaModelOption> =
        provider.models.mapNotNull { m ->
            imageSource(provider, m)?.let { MediaModelOption(customRef(provider.id, m), provider.name, m, it) }
        }

    /**
     * Request fields for an OpenAI-style `/images/generations` call. `response_format` is sent
     * only where it is documented and needed (DALL-E defaults to URLs, Gemini documents
     * `b64_json`); GPT image models always answer with base64 and reject the field.
     */
    fun openAiImageFields(baseUrl: String, model: String, aspectRatio: String?): Map<String, String> {
        val id = model.lowercase()
        val out = LinkedHashMap<String, String>()
        if (id.startsWith("dall-e-") || ProviderMetadata.isGeminiHost(baseUrl)) out["response_format"] = "b64_json"
        // Sizes are only mapped where the provider documents them; others keep their default.
        val ratio = aspectRatio?.trim()?.let { Regex("^(\\d{1,2}):(\\d{1,2})$").matchEntire(it) }
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }?.takeIf { it.first > 0 && it.second > 0 }
        if (ratio != null && ProviderMetadata.isOpenAiHost(baseUrl)) {
            val (w, h) = ratio
            out["size"] = when {
                id.startsWith("gpt-image-") -> if (w == h) "1024x1024" else if (w > h) "1536x1024" else "1024x1536"
                id == "dall-e-3" -> if (w == h) "1024x1024" else if (w > h) "1792x1024" else "1024x1792"
                else -> "1024x1024"
            }
        }
        return out
    }

    /** Image bytes or a link to them, from an OpenAI-style or OpenRouter Image API answer. */
    sealed class Payload {
        class Bytes(val bytes: ByteArray, val mime: String) : Payload()
        class Link(val url: String, val mime: String) : Payload()
    }

    /** Reads `data[0]` as `b64_json` (with `media_type`/`mime_type`), a data URL or a plain URL. */
    fun parseImageResponse(text: String): Payload {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IOException("the image model answered with something that is not JSON")
        val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw IOException("the image model returned no image")
        val mime = listOf("media_type", "mime_type").firstNotNullOfOrNull { (first[it] as? JsonPrimitive)?.content } ?: "image/png"
        (first["b64_json"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { b64 ->
            return Payload.Bytes(decode(b64.substringAfter("base64,")), mime)
        }
        val url = (first["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: throw IOException("the image model returned no image data")
        if (url.startsWith("data:")) return Payload.Bytes(decode(url.substringAfter("base64,")), url.substringAfter("data:").substringBefore(';'))
        return Payload.Link(url, mime)
    }

    private fun decode(b64: String): ByteArray = java.util.Base64.getMimeDecoder().decode(b64.trim())

    /** `{"error": {"message"}}` or `{"error": "text"}` from an error body. */
    fun errorDetail(text: String): String? = runCatching {
        when (val e = json.parseToJsonElement(text).jsonObject["error"]) {
            is JsonObject -> (e["message"] as? JsonPrimitive)?.content
            is JsonPrimitive -> e.content
            else -> null
        }
    }.getOrNull()
}
