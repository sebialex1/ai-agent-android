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
    /**
     * How the list says reasoning is requested: [FIELD_REASONING] (an OpenRouter-style `reasoning`
     * object), [FIELD_EFFORT] (`reasoning_effort`), [FIELD_NONE] (the model reasons but the API
     * documents no switch for it). Null: not stated, the provider's usual field is used.
     */
    val requestField: String? = null,
    /** The list names the model's default (thinking on or off when nothing is sent). */
    val defaultOn: Boolean? = null,
) {
    companion object {
        const val FIELD_REASONING = "reasoning"
        const val FIELD_EFFORT = "reasoning_effort"
        const val FIELD_NONE = "none"
    }
}

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
     * - Together-style lists: `type` (`image`, `chat`, `language`, ...);
     * - Vercel AI Gateway: `tags` (`reasoning`, `image-generation`), `modalities.output` and
     *   `reasoning_options` (effort values, toggle, budget_tokens);
     * - OpenRouter-compatible gateways: a `reasoning` block with `supported_efforts` / `mandatory`;
     * - LM Studio's native list: `capabilities.reasoning.{allowed_options, default}`;
     * - Ollama `/api/show`: a `capabilities` array with `thinking`, plus `thinking.values`.
     * Null when the entry carries none of them.
     */
    fun fromModelEntry(entry: JsonObject): ProviderModelMeta? {
        val caps = entry["capabilities"] as? JsonObject
        // LM Studio's native list (`GET /api/v1/models`): capabilities.reasoning.{allowed_options, default}.
        (caps?.get("reasoning") as? JsonObject)?.let { r -> return lmStudio(r) }
        // The same list without a reasoning entry: an LM Studio model that does not think.
        if (caps != null && caps.containsKey("trained_for_tool_use") && str(entry, "type") == "llm") {
            return ProviderModelMeta(image = false, reasoning = false, source = "LM Studio /api/v1/models capabilities")
        }
        // Ollama's /api/show style: capabilities is an array ("completion", "tools", "thinking", ...).
        (entry["capabilities"] as? JsonArray)?.let { arr ->
            val names = strings(arr).map { it.lowercase() }.toSet()
            if (names.isNotEmpty()) {
                return ProviderModelMeta(
                    image = false,
                    reasoning = "thinking" in names,
                    efforts = levels(entry["thinking"] as? JsonObject),
                    canDisable = thinkingValues(entry["thinking"] as? JsonObject).let { v -> if (v.isEmpty()) null else "false" in v || "none" in v },
                    source = "Ollama /api/show capabilities",
                )
            }
        }
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
            .ifEmpty { strings((entry["modalities"] as? JsonObject)?.get("output")) }
        val params = strings(entry["supported_parameters"]).toSet()
        val tags = strings(entry["tags"]).map { it.lowercase() }.toSet()
        val type = str(entry, "type")?.lowercase()
        val image = when {
            outputs.isNotEmpty() -> "image" in outputs
            "image-generation" in tags -> true
            type == "image" -> true
            type in setOf("chat", "language", "code", "embedding", "moderation", "rerank", "audio", "transcribe") -> false
            else -> null
        }
        // An OpenRouter-style `reasoning` block (OpenRouter-compatible gateways) and Vercel AI
        // Gateway's `reasoning_options` ([{type: "effort", values}, {type: "toggle"}, {type: "budget_tokens"}]).
        val block = entry["reasoning"] as? JsonObject
        val options = (entry["reasoning_options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val blockEfforts = strings(block?.get("supported_efforts"))
        val optionEfforts = options.filter { str(it, "type") == "effort" }.flatMap { strings(it["values"]) }
        val allEfforts = (blockEfforts + optionEfforts).map { it.lowercase() }.distinct()
        val efforts = ModelReasoning.ALL_EFFORTS.filter { it in allEfforts }
        val canDisable = when {
            bool(block, "mandatory") == true -> false
            "none" in allEfforts || options.any { str(it, "type") == "toggle" } -> true
            else -> null
        }
        val byParams = if (params.isEmpty()) null else
            params.any { it == "reasoning" || it == "reasoning_effort" || it == "include_reasoning" }
        val reasoning = when {
            byParams == true -> true
            block != null || options.isNotEmpty() -> true
            byParams == false -> false
            "reasoning" in tags -> true
            else -> null
        }
        if (image == null && reasoning == null) return null
        val field = when {
            "reasoning" in params -> ProviderModelMeta.FIELD_REASONING
            "reasoning_effort" in params -> ProviderModelMeta.FIELD_EFFORT
            else -> null
        }
        val source = when {
            options.isNotEmpty() -> "model list reasoning_options"
            block != null -> "model list reasoning block"
            params.isNotEmpty() -> "model list supported_parameters"
            "reasoning" in tags -> "model list tags"
            outputs.isNotEmpty() -> "model list output modalities"
            else -> "model list type"
        }
        return ProviderModelMeta(
            image = image, reasoning = reasoning, efforts = if (reasoning == true) efforts else emptyList(),
            canDisable = if (reasoning == true) canDisable else null, source = source, requestField = if (reasoning == true) field else null,
            defaultOn = bool(block, "default_enabled"),
        )
    }

    /**
     * LM Studio `capabilities.reasoning`: `allowed_options` (["off", "on"] or just ["on"]) and
     * `default`. Its OpenAI-compatible chat endpoint documents no reasoning parameter, so the
     * model is shown as thinking without a switch ([ProviderModelMeta.FIELD_NONE]).
     */
    private fun lmStudio(r: JsonObject): ProviderModelMeta {
        val allowed = strings(r["allowed_options"]).map { it.lowercase() }
        return ProviderModelMeta(
            image = false,
            reasoning = allowed.isEmpty() || allowed.any { it != "off" },
            canDisable = if (allowed.isEmpty()) null else "off" in allowed,
            source = "LM Studio /api/v1/models capabilities",
            requestField = ProviderModelMeta.FIELD_NONE,
            defaultOn = str(r, "default")?.lowercase()?.let { it != "off" },
        )
    }

    /** `thinking.values` of an Ollama /api/show answer, as strings ("true", "false", "low", ...). */
    private fun thinkingValues(thinking: JsonObject?): List<String> =
        (thinking?.get("values") as? JsonArray).orEmpty().mapNotNull { v ->
            (v as? JsonPrimitive)?.let { p -> p.booleanOrNull?.toString() ?: p.content.lowercase() }
        }

    /** Named thinking levels (highest first) from Ollama's `thinking.values`. */
    private fun levels(thinking: JsonObject?): List<String> =
        thinkingValues(thinking).filter { it != "true" && it != "false" }
            .sortedBy { v -> ModelReasoning.ALL_EFFORTS.indexOf(v).let { if (it < 0) Int.MAX_VALUE else it } }

    /**
     * One model's `POST /api/show` answer from a local Ollama server (`capabilities` array,
     * `thinking.values` / `thinking.default`, docs.ollama.com/capabilities/thinking). Null when
     * the answer has no capabilities.
     */
    fun fromOllamaShow(body: String): ProviderModelMeta? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val meta = fromModelEntry(root)?.takeIf { it.source.startsWith("Ollama") } ?: return null
        val default = (root["thinking"] as? JsonObject)?.get("default") as? JsonPrimitive
        return meta.copy(
            requestField = ProviderModelMeta.FIELD_EFFORT,
            defaultOn = default?.let { p -> (p.booleanOrNull?.toString() ?: p.content).lowercase() != "false" },
        )
    }

    /** Metadata for every entry of a `/models` body that has some, keyed like [CustomProviders] model ids. */
    fun fromModelList(body: String): Map<String, ProviderModelMeta> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyMap()
        val list = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyMap()
        val out = LinkedHashMap<String, ProviderModelMeta>()
        list.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val id = listOf("id", "key", "name", "model").firstNotNullOfOrNull { k -> str(o, k)?.takeIf { it.isNotBlank() } }
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
