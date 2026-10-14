package com.sebialex.agent.data.llm

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
 * OpenRouter (nothing runs on this phone).
 *
 * Follows https://docs.ollama.com/cloud and https://docs.ollama.com/api:
 * - chat uses the native `POST https://ollama.com/api/chat` endpoint (NDJSON streaming, native
 *   `tools` / `tool_calls`, and the `think` field for thinking models such as Gemma 4);
 * - model names are exactly those returned by `GET /api/tags` (e.g. `gemma4:31b`). The `:cloud` /
 *   `-cloud` names only work through a signed-in local Ollama app, never against ollama.com;
 * - capabilities (tools, thinking levels, context) come from `POST /api/show`;
 * - every request carries `Authorization: Bearer <key>` (keys: https://ollama.com/settings/keys).
 *
 * A 403 from ollama.com almost always means the key is wrong/revoked or the model needs a paid
 * plan ("this model requires a subscription"); the server's own message is surfaced verbatim.
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

    /** Native chat endpoint (`/api/chat`), see https://docs.ollama.com/api/chat. */
    val chatEndpoint: String get() = "$base/api/chat"

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
        // Hand-typed ids may be unknown to /api/show; they simply get no reasoning control.
        val name = resolveName(wireName(id))
        val info = try {
            info(name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Details could not be read: known thinking families still get their switch.
            // Otherwise nothing is known: say so instead of claiming "unsupported".
            if (knownThinking(name) || alwaysThinking(name)) OllamaModelInfo(true, true, emptyList(), null, null)
            else return ModelReasoning.unknown(id, "Ollama /api/show could not be read")
        }
        if (!info.thinking) return ModelReasoning.unsupported(id, "Ollama /api/show capabilities")
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
            wire = ReasoningWire.OLLAMA,
            source = "Ollama /api/show capabilities",
        )
    }

    /**
     * The exact name to send for [name]. ollama.com only accepts names from `/api/tags`, so a
     * bare family name (`gemma4`) or a local-app name (`gemma4:cloud`) is mapped to the listed
     * tag (`gemma4:31b`). Unchanged when the list cannot be read or has no better match.
     */
    suspend fun resolveName(name: String): String {
        val clean = normalizeName(name)
        val list = try {
            models()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return clean
        }
        if (list.isEmpty() || list.any { it.name == clean }) return clean
        val family = clean.substringBefore(':')
        return list.firstOrNull { it.name.substringBefore(':') == family }?.name ?: clean
    }

    private fun fetchModels(key: String): List<OllamaModel> {
        val conn = URL("$base/api/tags").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            val code = conn.responseCode
            if (code !in 200..299) throw httpError(code, conn, "reading the Ollama Cloud model list")
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
            conn.setRequestProperty("User-Agent", USER_AGENT)
            apiKey()?.let { conn.setRequestProperty("Authorization", "Bearer ${it.trim()}") }
            conn.outputStream.use { it.write(buildJsonObject { put("model", name) }.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw httpError(code, conn, "reading Ollama Cloud model details")
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
                // Gemma 4, gpt-oss, Qwen 3, DeepSeek R1/V3.x ... are thinking models even when the
                // cloud entry omits the capability.
                thinking = "thinking" in caps || (caps.isEmpty() && (knownThinking(name) || knownThinkingFamily(root["details"] as? JsonObject))) ||
                    alwaysThinking(name),
                thinkingValues = values,
                thinkingDefault = default,
                contextLength = context,
            )
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        internal const val USER_AGENT = "AgentForAndroid/1.0 (+https://github.com/sebialex1/ai-agent-android)"

        /** Model families that think (from ollama.com/search?c=thinking), used when `/api/show` is silent. */
        private val THINKING_FAMILIES = listOf(
            "gemma4", "gpt-oss", "qwen3", "deepseek-r1", "deepseek-v3.1", "deepseek-v3.2", "deepseek-v4",
            "kimi-k2-thinking", "kimi-k2.5", "magistral", "glm-4.6", "glm-4.7", "glm-5", "minimax-m2", "nemotron-3",
        )

        /** True for model names of a known thinking family, e.g. `gemma4:31b` or `qwen3-coder:480b`. */
        fun knownThinking(name: String): Boolean {
            val family = normalizeName(name).substringBefore(':').lowercase()
            return THINKING_FAMILIES.any { family.startsWith(it) }
        }

        /**
         * `details.family` / `details.families` of `/api/show` (e.g. `gptoss`, `qwen3`, `gemma4`)
         * name a known thinking family, for models whose own name says nothing (custom tags).
         * Only used when `/api/show` reports no capabilities at all.
         */
        fun knownThinkingFamily(details: JsonObject?): Boolean {
            if (details == null) return false
            val families = ((details["families"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } +
                listOfNotNull((details["family"] as? JsonPrimitive)?.content)).map { compact(it) }
            return families.any { f -> f.isNotEmpty() && THINKING_FAMILIES.any { t -> f.startsWith(compact(t)) } }
        }

        private fun compact(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** Gemma 4 always has a thinking mode (ollama.com/library/gemma4), whatever the metadata says. */
        fun alwaysThinking(name: String): Boolean = normalizeName(name).lowercase().startsWith("gemma4")

        /**
         * The native `think` value for a request: `false` when the user turned thinking off, the
         * model's named level when one is picked, otherwise `true`. Null for non-thinking models.
         */
        fun thinkValue(info: ModelReasoning, pref: ReasoningPreference): kotlinx.serialization.json.JsonElement? {
            if (!info.supported) return null
            val r = ReasoningRules.resolve(info, pref)
            return when {
                !r.on -> JsonPrimitive(false)
                r.effort != null && r.effort != BOOLEAN_ON_EFFORT -> JsonPrimitive(r.effort)
                r.effort != null && info.supportedEfforts != listOf(BOOLEAN_ON_EFFORT) -> JsonPrimitive(r.effort)
                else -> JsonPrimitive(true)
            }
        }

        /** Readable error with the server's own message (`{"error": "..."}` on the native API). */
        internal fun httpError(code: Int, conn: HttpURLConnection, what: String): IOException =
            httpError(code, runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty(), what)

        internal fun httpError(code: Int, body: String, what: String): IOException {
            val detail = errorMessage(body)
            val hint = when (code) {
                401 -> "the API key was rejected. Create a new key at $KEYS_URL and paste it in Settings"
                403 -> if (detail?.contains("subscription", ignoreCase = true) == true) "this model needs a paid Ollama plan (ollama.com/upgrade); pick a model your plan includes"
                    else "access denied. Check the API key at $KEYS_URL and that your plan includes this model"
                404 -> "model not found. Use a name from the Ollama Cloud model list (e.g. gemma4:31b, without -cloud)"
                429 -> "rate limited or usage limit reached; see ollama.com/settings/usage"
                else -> null
            }
            val parts = listOfNotNull(detail?.takeIf { it.isNotBlank() }, hint)
            return IOException("HTTP $code while $what" + if (parts.isEmpty()) "" else ": " + parts.joinToString(" \u2014 "))
        }

        /** `{"error": "text"}` or `{"error": {"message": "text"}}`. */
        internal fun errorMessage(body: String): String? = runCatching {
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
            when (val e = root["error"]) {
                is JsonPrimitive -> e.content
                is JsonObject -> (e["message"] as? JsonPrimitive)?.content
                else -> null
            }
        }.getOrNull() ?: body.trim().take(300).takeIf { it.isNotEmpty() && !it.startsWith("<") }

        /** Every Ollama Cloud model id in settings starts with this. */
        const val PREFIX = "ollama/"
        const val KEYS_URL = "https://ollama.com/settings/keys"
        /** Sent as `reasoning_effort` to switch a boolean-only thinking model on. */
        private const val BOOLEAN_ON_EFFORT = "high"

        fun isOllama(model: String): Boolean = model.startsWith(PREFIX)

        /** API model name for an app model id. */
        fun wireName(model: String): String = model.removePrefix(PREFIX)

        /**
         * Cleans a model name the user typed: trims it, drops an `ollama/` prefix and the
         * `-cloud` / `:cloud` suffix that only the local Ollama app uses (the direct cloud API
         * takes `gpt-oss:120b`, not `gpt-oss:120b-cloud`).
         */
        fun normalizeName(raw: String): String {
            var n = raw.trim().removePrefix(PREFIX).trim()
            if (n.endsWith(":cloud")) n = n.removeSuffix(":cloud")
            else if (n.endsWith("-cloud")) n = n.removeSuffix("-cloud")
            return n
        }
    }
}

