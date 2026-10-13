package com.sebialex.agent.data.llm

import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.agent.KeyPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * User-added providers that speak the OpenAI chat completions API (most do: OpenAI, Groq,
 * Mistral, DeepSeek, xAI, Together, Gemini's and Anthropic's compatibility layers, LM Studio,
 * Ollama's /v1, vLLM, LiteLLM, ...). Like SillyTavern's "Custom (OpenAI-compatible)" source,
 * LibreChat's custom endpoints, Open WebUI's connections and Jan's custom providers, each one is
 * just a base URL (the API root, normally ending in /v1, never /chat/completions), keys, and the
 * model ids its `/models` endpoint lists.
 *
 * Model ids are `custom/<provider id>/<model id>`; the model id may itself contain slashes.
 */
object CustomProviders {
    const val PREFIX = SecureSettings.CUSTOM_MODEL_PREFIX

    fun isCustom(model: String): Boolean = model.startsWith(PREFIX)

    fun modelId(providerId: String, model: String) = "$PREFIX$providerId/$model"

    /** (provider id, model id) of a custom model id, or null. */
    fun parse(model: String): Pair<String, String>? {
        if (!isCustom(model)) return null
        val rest = model.removePrefix(PREFIX)
        val id = rest.substringBefore('/')
        val name = rest.substringAfter('/', "")
        return if (id.isEmpty() || name.isEmpty()) null else id to name
    }

    /** Key ring id of a custom provider. */
    fun keyRing(providerId: String) = KeyPool.CUSTOM_PREFIX + providerId

    fun chatEndpoint(baseUrl: String) = baseUrl.trimEnd('/') + "/chat/completions"

    /** A well-known provider: its name, API root and where keys are made. */
    data class Preset(val id: String, val name: String, val baseUrl: String, val keysUrl: String? = null, val local: Boolean = false)

    /** Popular OpenAI-compatible providers, offered in the provider dropdown (plus "Custom / auto-detect"). */
    val PRESETS: List<Preset> = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "platform.openai.com/api-keys"),
        Preset("anthropic", "Anthropic", "https://api.anthropic.com/v1", "console.anthropic.com"),
        Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "aistudio.google.com/apikey"),
        Preset("groq", "Groq", "https://api.groq.com/openai/v1", "console.groq.com/keys"),
        Preset("mistral", "Mistral", "https://api.mistral.ai/v1", "console.mistral.ai"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "platform.deepseek.com"),
        Preset("xai", "xAI (Grok)", "https://api.x.ai/v1", "console.x.ai"),
        Preset("together", "Together AI", "https://api.together.xyz/v1", "api.together.ai"),
        Preset("fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1", "fireworks.ai"),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "cloud.cerebras.ai"),
        Preset("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai", "deepinfra.com"),
        Preset("perplexity", "Perplexity", "https://api.perplexity.ai", "perplexity.ai/settings/api"),
        Preset("huggingface", "Hugging Face", "https://router.huggingface.co/v1", "huggingface.co/settings/tokens"),
        Preset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "build.nvidia.com"),
        Preset("moonshot", "Moonshot (Kimi)", "https://api.moonshot.ai/v1", "platform.moonshot.ai"),
        Preset("zai", "Z.ai (GLM)", "https://api.z.ai/api/paas/v4", "z.ai"),
        Preset("lmstudio", "LM Studio", "http://localhost:1234/v1", local = true),
        Preset("ollama-local", "Ollama (local)", "http://localhost:11434/v1", local = true),
        Preset("llamacpp", "llama.cpp server", "http://localhost:8080/v1", local = true),
        Preset("vllm", "vLLM", "http://localhost:8000/v1", local = true),
        Preset("litellm", "LiteLLM proxy", "http://localhost:4000/v1", local = true),
        Preset("jan", "Jan", "http://127.0.0.1:1337/v1", local = true),
    )

    const val CUSTOM_PRESET = "custom"

    fun preset(id: String): Preset? = PRESETS.firstOrNull { it.id == id }

    /** The preset whose host matches [url], if any (so a pasted URL is recognised). */
    fun presetFor(url: String): Preset? {
        val host = hostOf(url) ?: return null
        return PRESETS.firstOrNull { !it.local && hostOf(it.baseUrl) == host }
            ?: PRESETS.firstOrNull { p -> p.local && hostOf(p.baseUrl) == host && portOf(p.baseUrl) == portOf(url) }
    }

    private fun hostOf(url: String): String? = runCatching { URL(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
    private fun portOf(url: String): Int = runCatching { URL(url).let { if (it.port == -1) it.defaultPort else it.port } }.getOrDefault(-1)

    /** Local-network addresses are fine over plain http; everything else defaults to https. */
    fun isLocalAddress(url: String): Boolean {
        val host = hostOf(url) ?: url.substringBefore('/').substringBefore(':')
        return host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            host.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*")) || host.endsWith(".local") || host == "0.0.0.0"
    }

    /**
     * Cleans a typed URL: adds a scheme (http for local addresses), drops a trailing slash and
     * any `/chat/completions`, `/completions` or `/models` suffix people paste by mistake.
     */
    fun normalize(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.contains("://")) u = (if (isLocalAddress("http://$u")) "http://" else "https://") + u
        for (suffix in listOf("/chat/completions", "/completions", "/models")) {
            if (u.endsWith(suffix, ignoreCase = true)) u = u.dropLast(suffix.length)
        }
        return u.trimEnd('/')
    }

    /** What auto-detection found. [baseUrl] is the API root to save when [compatible]. */
    data class Detection(
        val compatible: Boolean,
        val baseUrl: String,
        val name: String,
        val presetId: String,
        val models: List<String>,
        /** One readable line: what was found, or why nothing was. */
        val message: String,
        /** The endpoint answered but refused the key (401/403). */
        val needsKey: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Reply(val code: Int, val body: String)

    private fun request(url: String, key: String?, method: String = "GET", body: String? = null): Reply? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("Accept", "application/json")
            if (!key.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
                // Anthropic's own endpoints (model list) read the key from x-api-key.
                if ("anthropic.com" in url) {
                    conn.setRequestProperty("x-api-key", key.trim())
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                }
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText().take(400_000) }.orEmpty()
            Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Model ids from an OpenAI-style `{data: [{id}]}` list (or `{models: [{name|id}]}`); null when it is not one. */
    private fun modelIds(body: String): Pair<List<String>, String?>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val list = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return null
        val owners = HashMap<String, Int>()
        val ids = list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull (el as? JsonPrimitive)?.content
            (o["owned_by"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { owners[it] = (owners[it] ?: 0) + 1 }
            listOf("id", "name", "model").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } }
                ?.removePrefix("models/") // Gemini lists "models/<id>"
        }.distinct()
        val owner = owners.maxByOrNull { it.value }?.key
        return ids to owner
    }

    /** Candidate API roots for a typed URL: as typed, then the usual version paths. */
    private fun candidates(base: String): List<String> {
        val versioned = Regex("/(v\\d+[a-z0-9]*|openai|api/paas/v\\d+)$", RegexOption.IGNORE_CASE).containsMatchIn(base)
        return if (versioned) listOf(base) else listOf(base, "$base/v1", "$base/api/v1", "$base/openai/v1", "$base/v1beta/openai")
    }

    /**
     * Works out what is behind [rawUrl]: probes `<root>/models` for each candidate root (the OpenAI
     * model list; an answer with `data` means OpenAI-compatible), then Ollama's native
     * `/api/tags` (switching to its `/v1` root), then `POST <root>/chat/completions` with an empty
     * body (400/401/422 means the endpoint exists even without a model list). The provider is named
     * from a known preset host, the models' `owned_by`, or the host name.
     */
    suspend fun detect(rawUrl: String, key: String?): Detection = withContext(Dispatchers.IO) {
        val base = normalize(rawUrl)
        if (base.isEmpty() || runCatching { URL(base) }.isFailure) {
            return@withContext Detection(false, base, "", CUSTOM_PRESET, emptyList(), "That does not look like a URL.")
        }
        var refused: String? = null
        for (root in candidates(base)) {
            val r = request("$root/models", key) ?: continue
            when {
                r.code in 200..299 -> {
                    val parsed = modelIds(r.body) ?: continue
                    val (ids, owner) = parsed
                    val preset = presetFor(root)
                    val name = preset?.name ?: owner?.let { prettyOwner(it) } ?: hostName(root)
                    return@withContext Detection(
                        true, root, name, preset?.id ?: CUSTOM_PRESET, ids.sorted(),
                        "OpenAI-compatible \u00b7 ${ids.size} model${if (ids.size == 1) "" else "s"} at ${root.substringAfter("://")}",
                    )
                }
                r.code == 401 || r.code == 403 -> if (refused == null) refused = root
            }
        }
        // Ollama's native API: the OpenAI-compatible routes live under /v1.
        val origin = base.replace(Regex("/(v1|api|api/v1)$"), "")
        request("$origin/api/tags", key)?.takeIf { it.code in 200..299 }?.let { r ->
            val ids = modelIds(r.body)?.first.orEmpty()
            if (r.body.contains("\"models\"")) return@withContext Detection(
                true, "$origin/v1", "Ollama", presetFor("$origin/v1")?.id ?: CUSTOM_PRESET, ids.sorted(),
                "Ollama server \u00b7 ${ids.size} model${if (ids.size == 1) "" else "s"} \u00b7 using its OpenAI-compatible /v1",
            )
        }
        refused?.let { root ->
            val preset = presetFor(root)
            return@withContext Detection(
                true, root, preset?.name ?: hostName(root), preset?.id ?: CUSTOM_PRESET, emptyList(),
                if (key.isNullOrBlank()) "Found an OpenAI-compatible API that needs a key" else "The API answered but refused this key (401/403)",
                needsKey = true,
            )
        }
        // No model list: does a chat completions route exist at all?
        for (root in candidates(base)) {
            val r = request(chatEndpoint(root), key, method = "POST", body = "{}") ?: continue
            if (r.code in setOf(400, 401, 403, 422)) {
                val preset = presetFor(root)
                return@withContext Detection(
                    true, root, preset?.name ?: hostName(root), preset?.id ?: CUSTOM_PRESET, emptyList(),
                    "Chat completions found, but no model list \u2014 type a model id",
                    needsKey = r.code == 401 || r.code == 403,
                )
            }
        }
        Detection(false, base, hostName(base), CUSTOM_PRESET, emptyList(), "No OpenAI-compatible API found there. Try the URL ending in /v1.")
    }

    /** Re-reads a saved provider's model list with its active key; null on failure. */
    suspend fun fetchModels(baseUrl: String, key: String?): List<String>? = withContext(Dispatchers.IO) {
        val r = request(baseUrl.trimEnd('/') + "/models", key) ?: return@withContext null
        if (r.code !in 200..299) return@withContext null
        modelIds(r.body)?.first?.sorted()
    }

    private fun hostName(url: String): String =
        hostOf(url)?.removePrefix("api.")?.substringBeforeLast('.')?.replaceFirstChar { it.uppercase() } ?: "Custom provider"

    private fun prettyOwner(owner: String): String = when (owner.lowercase()) {
        "openai", "system", "openai-internal" -> "OpenAI"
        "library" -> "Ollama"
        "organization_owner" -> "Custom provider"
        else -> owner.replaceFirstChar { it.uppercase() }
    }
}
