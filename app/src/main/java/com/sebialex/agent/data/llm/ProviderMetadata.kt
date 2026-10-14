package com.sebialex.agent.data.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What a custom provider's own model list says about one model, saved with the provider so the
 * picker and the reasoning controls work offline. Every field is null when the list said nothing
 * about it: null means "unknown", never "unsupported".
 */
@Serializable
data class ProviderModelMeta(
    /** The list marks the model as an image generator (`type: "image"`, `output_modalities` with image). */
    val image: Boolean? = null,
    /** The list says whether the model can reason/think. */
    val reasoning: Boolean? = null,
    /** Effort levels the list names as supported (highest first), when it names any. */
    val efforts: List<String> = emptyList(),
    /** Thinking can be switched off (Anthropic `thinking.types.disabled`). */
    val canDisable: Boolean? = null,
    /** Anthropic adaptive thinking (`thinking.types.adaptive`). */
    val adaptive: Boolean? = null,
    /** Where this came from, shown in Settings, e.g. "Anthropic /v1/models capabilities". */
    val source: String = "",
)

/** Reads capability fields from model-list responses. Pure functions, so they are unit tested. */
object ProviderMetadata {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun bool(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.booleanOrNull
    private fun strings(el: Any?) = (el as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

    /**
     * Capability metadata of one entry of an OpenAI-style `/models` list. Recognised fields:
     * - Anthropic `/v1/models`: `capabilities.thinking.{supported,types.{adaptive,disabled,enabled}}`
     *   and `capabilities.effort.{low,medium,high,xhigh,max}.supported`;
     * - OpenRouter-style lists: `architecture.output_modalities`, `supported_parameters`
     *   (`reasoning`, `reasoning_effort`, `include_reasoning`);
     * - Together-style lists: `type` (`image`, `chat`, `language`, ...).
     * Null when the entry carries none of them.
     */
    fun fromModelEntry(entry: JsonObject): ProviderModelMeta? {
        val caps = entry["capabilities"] as? JsonObject
        val thinking = caps?.get("thinking") as? JsonObject
        if (thinking != null) {
            val types = thinking["types"] as? JsonObject
            fun type(k: String) = bool(types?.get(k) as? JsonObject, "supported")
            val effort = caps["effort"] as? JsonObject
            val efforts = if (bool(effort, "supported") == true) {
                ModelReasoning.ALL_EFFORTS.filter { bool(effort?.get(it) as? JsonObject, "supported") == true }
            } else emptyList()
            return ProviderModelMeta(
                image = false,
                reasoning = bool(thinking, "supported") ?: false,
                efforts = efforts,
                canDisable = type("disabled"),
                adaptive = type("adaptive"),
                source = "Anthropic /v1/models capabilities",
            )
        }
        val outputs = strings((entry["architecture"] as? JsonObject)?.get("output_modalities"))
        val params = strings(entry["supported_parameters"]).toSet()
        val type = str(entry, "type")?.lowercase()
        val image = when {
            outputs.isNotEmpty() -> "image" in outputs
            type == "image" -> true
            type in setOf("chat", "language", "code", "embedding", "moderation", "rerank", "audio", "transcribe") -> false
            else -> null
        }
        val reasoning = if (params.isEmpty()) null else
            params.any { it == "reasoning" || it == "reasoning_effort" || it == "include_reasoning" }
        if (image == null && reasoning == null) return null
        val source = when {
            params.isNotEmpty() -> "model list supported_parameters"
            outputs.isNotEmpty() -> "model list output_modalities"
            else -> "model list type"
        }
        return ProviderModelMeta(image = image, reasoning = reasoning, source = source)
    }

    /** Metadata for every entry of a `/models` body that has some, keyed like [CustomProviders] model ids. */
    fun fromModelList(body: String): Map<String, ProviderModelMeta> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyMap()
        val list = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyMap()
        val out = LinkedHashMap<String, ProviderModelMeta>()
        list.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = listOf("id", "name", "model").firstNotNullOfOrNull { k -> str(o, k)?.takeIf { it.isNotBlank() } }
                ?.removePrefix("models/") ?: return@forEach
            fromModelEntry(o)?.let { out[id] = it }
        }
        return out
    }

    /**
     * Gemini's native `GET /v1beta/models` answer: each model's `thinking` boolean ("Whether the
     * model supports thinking"). The OpenAI-compatible `/models` route does not carry it.
     */
    fun fromGeminiNative(body: String): Map<String, ProviderModelMeta> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, ProviderModelMeta>()
        (root["models"] as? JsonArray).orEmpty().forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = str(o, "name")?.removePrefix("models/") ?: return@forEach
            val thinking = bool(o, "thinking")
            out[id] = ProviderModelMeta(reasoning = thinking ?: false, source = "Gemini models.list")
        }
        return out
    }

    fun isGeminiHost(baseUrl: String) = host(baseUrl) == "generativelanguage.googleapis.com"
    fun isAnthropicHost(baseUrl: String) = host(baseUrl) == "api.anthropic.com"
    fun isOpenAiHost(baseUrl: String) = host(baseUrl) == "api.openai.com"
    fun isXaiHost(baseUrl: String) = host(baseUrl) == "api.x.ai"
    fun isDeepSeekHost(baseUrl: String) = host(baseUrl) == "api.deepseek.com"

    fun host(url: String): String? = runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
}
