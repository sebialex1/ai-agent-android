package com.sebialex.agent.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What one OpenRouter model says about reasoning, read from the live `GET /api/v1/models` entry:
 * its `reasoning` block plus which reasoning request parameters appear in `supported_parameters`.
 */
data class ModelReasoning(
    val modelId: String,
    /** The model entry has a `reasoning` block. Absent for non-reasoning models and routers. */
    val hasBlock: Boolean,
    /**
     * `supported_efforts`: null when the field is omitted (no effort selection), [ALL_EFFORTS]
     * when it is explicitly null (no allowlist), otherwise the listed values (highest first).
     */
    val supportedEfforts: List<String>?,
    val defaultEffort: String?,
    val defaultEnabled: Boolean?,
    val mandatory: Boolean,
    val supportsMaxTokens: Boolean,
    val acceptsReasoning: Boolean,
    val acceptsIncludeReasoning: Boolean,
    val acceptsReasoningEffort: Boolean,
    /** How the choice is put on the wire (see [ReasoningRules.requestFields]). */
    val wire: ReasoningWire = ReasoningWire.OPENROUTER,
    /**
     * False when nothing authoritative said whether the model reasons (no metadata, model not in
     * the list, imported on-device model not loaded yet): "unknown", not "unsupported".
     */
    val known: Boolean = true,
    /**
     * The provider has its own default worth keeping: "Auto" sends no reasoning field at all, so
     * the model decides (Anthropic, Gemini, OpenAI-style `reasoning_effort` providers).
     */
    val allowsAuto: Boolean = false,
    /**
     * The provider returns reasoning text the app can show. Separate from [supported]: Anthropic's
     * and Gemini's OpenAI-compatible layers think but return no thinking text.
     */
    val surfacesText: Boolean = true,
    /** Anthropic: adaptive thinking (`{"type": "adaptive"}`) is available for this model. */
    val adaptive: Boolean = false,
    /** Where this information came from, shown next to the control (e.g. "OpenRouter model list"). */
    val source: String = "",
) {
    /** Supported, unsupported, or unknown (never guessed as unsupported). */
    val support: ReasoningSupport
        get() = when {
            !known -> ReasoningSupport.UNKNOWN
            supported -> ReasoningSupport.SUPPORTED
            else -> ReasoningSupport.UNSUPPORTED
        }

    /** Reasoning can be controlled at all: it has a reasoning block and accepts a reasoning parameter. */
    val supported: Boolean get() = hasBlock && (acceptsReasoning || acceptsReasoningEffort || acceptsIncludeReasoning)

    /** Reasoning can be turned off. Never for mandatory models; `include_reasoning` alone only hides it. */
    val canDisable: Boolean get() = supported && !mandatory && (acceptsReasoning || acceptsReasoningEffort)

    /** Effort levels the user can pick when reasoning is on. "none" is the off switch, never a level. */
    val effortChoices: List<String>
        get() = if (!supported || !(acceptsReasoning || acceptsReasoningEffort)) emptyList()
        else supportedEfforts.orEmpty().filter { it != "none" }

    /** "none" listed in supported_efforts means the model is switched off with effort "none". */
    val disablesWithEffortNone: Boolean get() = supportedEfforts?.contains("none") == true

    /** Default state when the user has not touched the toggle for this model. */
    val defaultOn: Boolean
        get() = when {
            !supported -> false
            mandatory || !canDisable -> true
            defaultEnabled != null -> defaultEnabled && defaultEffort != "none"
            else -> false
        }

    /** Preselected effort: the model default when it is a real level, otherwise none. */
    val preselectedEffort: String?
        get() = defaultEffort?.takeIf { it != "none" && it in effortChoices }

    companion object {
        val ALL_EFFORTS = listOf("max", "xhigh", "high", "medium", "low", "minimal")

        fun unsupported(model: String, source: String = "") =
            ModelReasoning(model, false, null, null, null, false, false, false, false, false, source = source)

        /** Nothing authoritative is known: no control is offered, and the UI says "unknown". */
        fun unknown(model: String, source: String) = unsupported(model, source).copy(known = false)

        /**
         * A model with reasoning on a provider other than OpenRouter: [efforts] are the levels to
         * offer (highest first, may be empty for a plain on/off), [canDisable] whether it can be
         * switched off, [offWithEffortNone] when "off" is sent as effort "none".
         */
        fun controlled(
            model: String,
            wire: ReasoningWire,
            efforts: List<String>,
            canDisable: Boolean,
            defaultOn: Boolean,
            allowsAuto: Boolean,
            surfacesText: Boolean,
            source: String,
            offWithEffortNone: Boolean = false,
            adaptive: Boolean = false,
        ) = ModelReasoning(
            modelId = model,
            hasBlock = true,
            supportedEfforts = (efforts.filter { it != "none" } + if (offWithEffortNone) listOf("none") else emptyList()).ifEmpty { null },
            defaultEffort = null,
            defaultEnabled = defaultOn,
            mandatory = !canDisable,
            supportsMaxTokens = false,
            acceptsReasoning = false,
            acceptsIncludeReasoning = false,
            acceptsReasoningEffort = true,
            wire = wire,
            allowsAuto = allowsAuto,
            surfacesText = surfacesText,
            adaptive = adaptive,
            source = source,
        )

        /**
         * An on-device model. Models with a thinking mode get a plain on/off switch (off by
         * default, since thinking is slow on a phone); others have no reasoning control.
         */
        fun local(model: String, thinking: Boolean, source: String = "on-device model") = if (!thinking) unsupported(model, source).copy(wire = ReasoningWire.LOCAL) else ModelReasoning(
            modelId = model,
            hasBlock = true,
            supportedEfforts = null,
            defaultEffort = null,
            defaultEnabled = false,
            mandatory = false,
            supportsMaxTokens = false,
            acceptsReasoning = true,
            acceptsIncludeReasoning = false,
            acceptsReasoningEffort = false,
            wire = ReasoningWire.LOCAL,
            source = source,
        )

        fun parse(entry: JsonObject): ModelReasoning {
            val id = (entry["id"] as? JsonPrimitive)?.content.orEmpty()
            val params = (entry["supported_parameters"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            val block = entry["reasoning"] as? JsonObject
            val efforts: List<String>? = when {
                block == null || !block.containsKey("supported_efforts") -> null
                block["supported_efforts"] is JsonNull -> ALL_EFFORTS
                else -> (block["supported_efforts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            }
            return ModelReasoning(
                modelId = id,
                hasBlock = block != null,
                supportedEfforts = efforts,
                defaultEffort = (block?.get("default_effort") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                defaultEnabled = (block?.get("default_enabled") as? JsonPrimitive)?.booleanOrNull,
                mandatory = (block?.get("mandatory") as? JsonPrimitive)?.booleanOrNull == true,
                supportsMaxTokens = (block?.get("supports_max_tokens") as? JsonPrimitive)?.booleanOrNull == true,
                acceptsReasoning = "reasoning" in params,
                acceptsIncludeReasoning = "include_reasoning" in params,
                acceptsReasoningEffort = "reasoning_effort" in params,
                source = "OpenRouter model list",
            )
        }
    }
}

/** The user's stored choice for one model. Null fields mean "not set, use the model default". */
data class ReasoningPreference(val enabled: Boolean?, val effort: String?)

/**
 * What the toggle shows and what gets sent, after applying the model's rules to the user's choice.
 * [auto] is the provider-default state: no reasoning field is sent and [on] is only the expected default.
 */
data class ResolvedReasoning(val on: Boolean, val effort: String?, val auto: Boolean = false)

enum class ReasoningSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** The request shape each provider family uses for reasoning. */
enum class ReasoningWire {
    /** OpenRouter: `reasoning: {effort|enabled}`, or `reasoning_effort` / `include_reasoning`. */
    OPENROUTER,

    /** Ollama's native `think`: true/false or a named level (see [OllamaCloud.thinkValue]). */
    OLLAMA,

    /** OpenAI-style `reasoning_effort` (OpenAI, DeepSeek, most OpenAI-compatible servers). */
    OPENAI_EFFORT,

    /** Gemini's OpenAI layer: `reasoning_effort` low/medium/high, "none" only where thinking can be off. */
    GEMINI,

    /** Anthropic's OpenAI layer: `thinking: {type: adaptive|enabled|disabled}`. */
    ANTHROPIC,

    /** On-device LiteRT-LM: a boolean passed to the engine (see LocalBrain). */
    LOCAL,
}

/** One option of the thinking control. [id] is [ReasoningRules.AUTO], [ReasoningRules.OFF], [ReasoningRules.ON] or an effort level. */
data class ReasoningChoice(val id: String, val label: String)

object ReasoningRules {

    const val AUTO = "auto"
    const val OFF = "off"
    const val ON = "on"

    /** Anthropic manual thinking (models without adaptive thinking): budget and the max_tokens that must exceed it. */
    const val ANTHROPIC_BUDGET = 4096
    const val ANTHROPIC_MAX_TOKENS = 16384

    /**
     * The options the thinking control offers for this model, only what its provider supports:
     * "Auto" where the provider has its own default, "Off" where it can be switched off, then
     * either its effort levels or a plain "On". Empty when there is nothing to control.
     */
    fun choices(info: ModelReasoning): List<ReasoningChoice> {
        if (!info.supported) return emptyList()
        return buildList {
            if (info.allowsAuto) add(ReasoningChoice(AUTO, "Auto"))
            if (info.canDisable) add(ReasoningChoice(OFF, "Off"))
            val levels = info.effortChoices
            if (levels.size > 1) levels.forEach { add(ReasoningChoice(it, label(it))) } else add(ReasoningChoice(ON, "On"))
        }
    }

    /** Which option is selected for [pref]; null when reasoning is on without a level and no plain "On" is offered. */
    fun selected(info: ModelReasoning, pref: ReasoningPreference): String? {
        val r = resolve(info, pref)
        val ids = choices(info).map { it.id }
        val id = when {
            r.auto -> AUTO
            !r.on -> OFF
            r.effort != null && r.effort in ids -> r.effort
            else -> ON
        }
        return id.takeIf { it in ids }
    }

    /** The preference to store for an option (both fields null = Auto / model default). */
    fun preferenceFor(choice: String): ReasoningPreference = when (choice) {
        AUTO -> ReasoningPreference(null, null)
        OFF -> ReasoningPreference(false, null)
        ON -> ReasoningPreference(true, null)
        else -> ReasoningPreference(true, choice)
    }

    fun label(effort: String): String = when (effort) {
        "xhigh" -> "X-high"
        else -> effort.replaceFirstChar { it.uppercase() }
    }

    fun resolve(info: ModelReasoning, pref: ReasoningPreference): ResolvedReasoning {
        if (!info.supported) return ResolvedReasoning(false, null)
        if (info.allowsAuto && pref.enabled == null && pref.effort == null) return ResolvedReasoning(info.defaultOn, null, auto = true)
        val on = if (!info.canDisable) true else pref.enabled ?: info.defaultOn
        val effort = if (!on) null else pref.effort?.takeIf { it in info.effortChoices } ?: info.preselectedEffort
        return ResolvedReasoning(on, effort)
    }

    /**
     * Top-level request fields for a chat completion. Empty when the model has no controllable
     * reasoning, so nothing unsupported is ever sent. When the toggle is off and the model can be
     * switched off, reasoning is disabled explicitly so it never silently defaults on.
     */
    fun requestFields(info: ModelReasoning, pref: ReasoningPreference): JsonObject {
        if (!info.supported) return JsonObject(emptyMap())
        val r = resolve(info, pref)
        return when (info.wire) {
            ReasoningWire.OPENROUTER, ReasoningWire.OLLAMA -> openRouterFields(info, r)
            // The engine takes a boolean; nothing goes into a JSON request.
            ReasoningWire.LOCAL -> JsonObject(emptyMap())
            ReasoningWire.OPENAI_EFFORT, ReasoningWire.GEMINI -> buildJsonObject {
                when {
                    r.auto -> Unit
                    !r.on -> if (info.disablesWithEffortNone) put("reasoning_effort", "none")
                    r.effort != null -> put("reasoning_effort", r.effort)
                }
            }
            ReasoningWire.ANTHROPIC -> buildJsonObject {
                when {
                    r.auto -> Unit
                    !r.on -> putJsonObject("thinking") { put("type", "disabled") }
                    info.adaptive -> putJsonObject("thinking") { put("type", "adaptive") }
                    else -> {
                        putJsonObject("thinking") {
                            put("type", "enabled")
                            put("budget_tokens", ANTHROPIC_BUDGET)
                        }
                        put("max_tokens", ANTHROPIC_MAX_TOKENS)
                    }
                }
            }
        }
    }

    private fun openRouterFields(info: ModelReasoning, r: ResolvedReasoning): JsonObject {
        return buildJsonObject {
            when {
                info.acceptsReasoning -> putJsonObject("reasoning") {
                    when {
                        r.on && r.effort != null -> put("effort", r.effort)
                        r.on -> put("enabled", true)
                        info.disablesWithEffortNone -> put("effort", "none")
                        else -> put("enabled", false)
                    }
                }
                info.acceptsReasoningEffort -> {
                    when {
                        r.on && r.effort != null -> put("reasoning_effort", r.effort)
                        !r.on -> put("reasoning_effort", "none")
                    }
                    if (info.acceptsIncludeReasoning && r.on) put("include_reasoning", true)
                }
                info.acceptsIncludeReasoning -> put("include_reasoning", true)
            }
        }
    }
}

/** One model of the live OpenRouter catalog, for the model picker. */
data class CatalogModel(
    val id: String,
    /** Display name without the "Provider: " prefix, e.g. "Claude Sonnet 4.5". */
    val name: String,
    /** Provider part of the name or id, e.g. "Anthropic". */
    val vendor: String,
    val contextLength: Long?,
    val thinking: Boolean,
    val tools: Boolean,
    val free: Boolean,
)

/**
 * Live model metadata from OpenRouter (`GET /api/v1/models`). The list is cached briefly so each
 * chat request reads current metadata without refetching the whole catalog every tool step.
 */
class OpenRouterModels(
    private val apiKey: () -> String?,
    private val endpoint: String = "https://openrouter.ai/api/v1/models",
    private val ttlMs: Long = 10 * 60_000L,
    /**
     * Reasoning info for models that OpenRouter does not serve (on-device models, other cloud
     * providers). Non-null answers win and the OpenRouter catalog is not fetched for them.
     */
    private val other: suspend (String) -> ModelReasoning? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()
    private var cache: Map<String, ModelReasoning> = emptyMap()
    private var listed: List<CatalogModel> = emptyList()
    private var fetchedAt = 0L

    /** The live OpenRouter model list (public endpoint, cached like the reasoning metadata). */
    suspend fun list(refresh: Boolean = false): List<CatalogModel> {
        catalog(refresh)
        return lock.withLock { listed }
    }

    /** Reasoning metadata for [model]. Unknown models and routers come back as unsupported. */
    suspend fun reasoning(model: String, refresh: Boolean = false): ModelReasoning {
        other(model)?.let { return it }
        val all = catalog(refresh)
        return all[model] ?: all[model.substringBefore(':')]?.takeIf { model.contains(':') } ?: ModelReasoning.unsupported(model)
    }

    private var images: List<MediaModelOption> = emptyList()
    private var imagesAt = 0L

    /**
     * OpenRouter's image models, from the Image API's own catalog (`GET /api/v1/images/models`),
     * so only models that `POST /api/v1/images` serves are offered for generate_image.
     */
    suspend fun imageModels(refresh: Boolean = false): List<MediaModelOption> = lock.withLock {
        if (!refresh && images.isNotEmpty() && System.currentTimeMillis() - imagesAt < ttlMs) return@withLock images
        val body = withContext(Dispatchers.IO) {
            val conn = URL(MediaModels.IMAGES_ENDPOINT_OPENROUTER).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Accept", "application/json")
                apiKey()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code while reading OpenRouter's image models")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        images = MediaModels.parseOpenRouterImageModels(body)
        imagesAt = System.currentTimeMillis()
        images
    }

    private suspend fun catalog(refresh: Boolean): Map<String, ModelReasoning> = lock.withLock {
        val fresh = System.currentTimeMillis() - fetchedAt < ttlMs
        if (!refresh && fresh && cache.isNotEmpty()) return@withLock cache
        val (parsed, models) = withContext(Dispatchers.IO) { fetch() }
        cache = parsed
        listed = models
        fetchedAt = System.currentTimeMillis()
        parsed
    }

    private fun fetch(): Pair<Map<String, ModelReasoning>, List<CatalogModel>> {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            apiKey()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code while reading the model list")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val data = json.parseToJsonElement(text).jsonObject["data"] as? JsonArray
                ?: throw IOException("the model list had no data")
            val out = HashMap<String, ModelReasoning>(data.size * 2)
            val models = ArrayList<CatalogModel>(data.size)
            data.forEach { el ->
                val entry = el as? JsonObject ?: return@forEach
                val info = ModelReasoning.parse(entry)
                if (info.modelId.isNotEmpty()) out[info.modelId] = info
                (entry["canonical_slug"] as? JsonPrimitive)?.content?.let { slug -> out.putIfAbsent(slug, info) }
                if (info.modelId.isNotEmpty()) {
                    val full = (entry["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: info.modelId
                    val vendor = full.substringBefore(": ", "").ifBlank { info.modelId.substringBefore('/').replaceFirstChar { it.uppercase() } }
                    val params = (entry["supported_parameters"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                    val pricing = entry["pricing"] as? JsonObject
                    val free = info.modelId.endsWith(":free") ||
                        listOf("prompt", "completion").all { k -> (pricing?.get(k) as? JsonPrimitive)?.content?.toDoubleOrNull() == 0.0 }
                    models += CatalogModel(
                        id = info.modelId,
                        name = full.substringAfter(": ", full),
                        vendor = vendor,
                        contextLength = (entry["context_length"] as? JsonPrimitive)?.content?.toLongOrNull(),
                        thinking = info.supported,
                        tools = "tools" in params,
                        free = free,
                    )
                }
            }
            return out to models.sortedWith(compareBy({ it.vendor.lowercase() }, { it.name.lowercase() }))
        } finally {
            conn.disconnect()
        }
    }
}
