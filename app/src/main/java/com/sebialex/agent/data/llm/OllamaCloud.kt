package com.sebialex.agent.data.llm

import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One model offered by Ollama Cloud (`GET https://ollama.com/api/tags`). */
data class OllamaModel(
    /** Exact API name, for example `gpt-oss:120b`. */
    val name: String,
    /** App model id: [OllamaCloud.PREFIX] + [name]. */
    val id: String,
    val modifiedAt: String?,
)

/** What `POST /api/show` says about one model. */
data class OllamaModelInfo(
    val tools: Boolean,
    val thinking: Boolean,
    /** `thinking.values`: `false`/`true` and/or named levels such as `low`, `high`, `max`. */
    val thinkingValues: List<String>,
    val thinkingDefault: String?,
    val contextLength: Long?,
)

/**
 * Ollama Cloud: Ollama's hosted models at `https://ollama.com`, a separate cloud provider next to
 * OpenRouter (nothing runs on this phone). Chat goes through its OpenAI-compatible endpoint
 * `POST /v1/chat/completions` (streaming, tools, `reasoning_effort`), so the same request/
 * streaming code as OpenRouter is reused. The model list comes from `GET /api/tags` and per-model
 * capabilities (tools, thinking levels, context) from `POST /api/show`. Every request carries
 * `Authorization: Bearer <key>`; keys are created at https://ollama.com/settings/keys.
 */
class OllamaCloud(
    private val apiKey: () -> String?,
    private val base: String = "https://ollama.com",
    private val ttlMs: Long = 10 * 60_000L,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val listLock = Mutex()
    private var cached: List<OllamaModel> = emptyList()
    private var fetchedAt = 0L
    private val infoLock = Mutex()
    private val infos = HashMap<String, OllamaModelInfo>()

    val chatEndpoint: String get() = "$base/v1/chat/completions"

    /** Models available to this key, by name. Empty without a key (nothing is requested then). */
    suspend fun models(refresh: Boolean = false): List<OllamaModel> {
        val key = apiKey() ?: return emptyList()
        return listLock.withLock {
            val fresh = System.currentTimeMillis() - fetchedAt < ttlMs
            if (!refresh && fresh && cached.isNotEmpty()) return@withLock cached
            val parsed = withContext(Dispatchers.IO) { fetchModels(key) }
            cached = parsed
            fetchedAt = System.currentTimeMillis()
            parsed
        }
    }

    /** Capabilities of [name] (API name, without prefix). Cached for the app's lifetime. */
    suspend fun info(name: String): OllamaModelInfo {
        infoLock.withLock { infos[name] }?.let { return it }
        val parsed = withContext(Dispatchers.IO) { fetchInfo(name) }
        infoLock.withLock { infos[name] = parsed }
        return parsed
    }

    /** Reasoning controls for an Ollama model, from its `thinking` metadata. */
    suspend fun reasoning(id: String): ModelReasoning {
        val info = info(wireName(id))
        if (!info.thinking) return ModelReasoning.unsupported(id)
        val values = info.thinkingValues
        val levels = values.filter { it != "true" && it != "false" }
            .sortedBy { v -> ModelReasoning.ALL_EFFORTS.indexOf(v).let { if (it < 0) Int.MAX_VALUE else it } }
        // Ollama maps any recognised effort to "on" for boolean-only models and "none" to "off",
        // so a boolean model gets one hidden level that switches thinking on.
        val efforts = levels.ifEmpty { listOf(BOOLEAN_ON_EFFORT) }
        val canDisable = "false" in values || values.isEmpty()
        val default = info.thinkingDefault
        return ModelReasoning(
            modelId = id,
            hasBlock = true,
            supportedEfforts = efforts,
            defaultEffort = default?.takeIf { it in levels } ?: if (levels.isEmpty()) BOOLEAN_ON_EFFORT else null,
            defaultEnabled = default != "false",
            mandatory = !canDisable,
            supportsMaxTokens = false,
            acceptsReasoning = false,
            acceptsIncludeReasoning = false,
            acceptsReasoningEffort = true,
        )
    }

    private fun fetchModels(key: String): List<OllamaModel> {
        val conn = URL("$base/api/tags").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw IOException("Ollama Cloud rejected the API key (HTTP $code)")
            if (code !in 200..299) throw IOException("HTTP $code while reading the Ollama Cloud model list")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val models = json.parseToJsonElement(text).jsonObject["models"] as? JsonArray
                ?: throw IOException("the Ollama Cloud model list had no models")
            return models.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = ((o["model"] ?: o["name"]) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OllamaModel(name = name, id = PREFIX + name, modifiedAt = (o["modified_at"] as? JsonPrimitive)?.content)
            }.distinctBy { it.name }.sortedBy { it.name.lowercase() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchInfo(name: String): OllamaModelInfo {
        val conn = URL("$base/api/show").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            apiKey()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(buildJsonObject { put("model", name) }.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code while reading Ollama Cloud model details")
            val root = json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val caps = (root["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            val thinking = root["thinking"] as? JsonObject
            val values = (thinking?.get("values") as? JsonArray).orEmpty().mapNotNull { v ->
                (v as? JsonPrimitive)?.let { p -> p.booleanOrNull?.toString() ?: p.content }
            }
            val default = (thinking?.get("default") as? JsonPrimitive)?.let { p -> p.booleanOrNull?.toString() ?: p.content }
            val context = (root["model_info"] as? JsonObject)?.entries
                ?.firstOrNull { it.key.endsWith(".context_length") }
                ?.let { (it.value as? JsonPrimitive)?.longOrNull }
            return OllamaModelInfo(
                tools = "tools" in caps,
                thinking = "thinking" in caps,
                thinkingValues = values,
                thinkingDefault = default,
                contextLength = context,
            )
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Every Ollama Cloud model id in settings starts with this. */
        const val PREFIX = "ollama/"
        const val KEYS_URL = "https://ollama.com/settings/keys"
        /** Sent as `reasoning_effort` to switch a boolean-only thinking model on. */
        private const val BOOLEAN_ON_EFFORT = "high"

        fun isOllama(model: String): Boolean = model.startsWith(PREFIX)

        /** API model name for an app model id. */
        fun wireName(model: String): String = model.removePrefix(PREFIX)
    }
}

/**
 * Agent brain for Ollama Cloud. It reads the model's capabilities first (tools are only offered
 * to models that support them), then runs the shared OpenAI-compatible chat code against
 * `ollama.com/v1/chat/completions`.
 */
class OllamaBrain(
    private val cloud: OllamaCloud,
    private val apiKey: String,
    private val model: String,
    private val reasoningFields: suspend () -> JsonObject,
) : AgentBrain {
    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
        val name = OllamaCloud.wireName(model)
        // If details cannot be read, still try with tools: most cloud models support them.
        val tools = try {
            cloud.info(name).tools
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
        return OpenRouterBrain(
            apiKey = apiKey,
            model = name,
            endpoint = cloud.chatEndpoint,
            reasoningFields = reasoningFields,
            provider = "Ollama Cloud",
            openRouterExtras = false,
            withTools = tools,
        ).next(ctx, onReasoning)
    }
}
