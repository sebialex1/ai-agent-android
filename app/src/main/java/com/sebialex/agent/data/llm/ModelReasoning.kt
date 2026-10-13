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
) {
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

        fun unsupported(model: String) = ModelReasoning(model, false, null, null, null, false, false, false, false, false)

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
            )
        }
    }
}

/** The user's stored choice for one model. Null fields mean "not set, use the model default". */
data class ReasoningPreference(val enabled: Boolean?, val effort: String?)

/** What the toggle shows and what gets sent, after applying the model's rules to the user's choice. */
data class ResolvedReasoning(val on: Boolean, val effort: String?)

object ReasoningRules {

    fun resolve(info: ModelReasoning, pref: ReasoningPreference): ResolvedReasoning {
        if (!info.supported) return ResolvedReasoning(false, null)
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

/**
 * Live model metadata from OpenRouter (`GET /api/v1/models`). The list is cached briefly so each
 * chat request reads current metadata without refetching the whole catalog every tool step.
 */
class OpenRouterModels(
    private val apiKey: () -> String?,
    private val endpoint: String = "https://openrouter.ai/api/v1/models",
    private val ttlMs: Long = 10 * 60_000L,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Mutex()
    private var cache: Map<String, ModelReasoning> = emptyMap()
    private var fetchedAt = 0L

    /** Reasoning metadata for [model]. Unknown models and routers come back as unsupported. */
    suspend fun reasoning(model: String, refresh: Boolean = false): ModelReasoning {
        val all = catalog(refresh)
        return all[model] ?: all[model.substringBefore(':')]?.takeIf { model.contains(':') } ?: ModelReasoning.unsupported(model)
    }

    private suspend fun catalog(refresh: Boolean): Map<String, ModelReasoning> = lock.withLock {
        val fresh = System.currentTimeMillis() - fetchedAt < ttlMs
        if (!refresh && fresh && cache.isNotEmpty()) return@withLock cache
        val parsed = withContext(Dispatchers.IO) { fetch() }
        cache = parsed
        fetchedAt = System.currentTimeMillis()
        parsed
    }

    private fun fetch(): Map<String, ModelReasoning> {
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
            data.forEach { el ->
                val entry = el as? JsonObject ?: return@forEach
                val info = ModelReasoning.parse(entry)
                if (info.modelId.isNotEmpty()) out[info.modelId] = info
                (entry["canonical_slug"] as? JsonPrimitive)?.content?.let { slug -> out.putIfAbsent(slug, info) }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
