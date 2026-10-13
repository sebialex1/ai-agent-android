package com.sebialex.agent.data.llm

import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Writes a short conversation title with the selected cloud model once the first answer is in,
 * so the list shows what the chat is about instead of its first words. One small non-streaming
 * request, no tools. On-device models (and any failure) keep the local fallback title.
 */
class TitleWriter(
    private val settings: SecureSettings,
    private val ollama: OllamaCloud,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A clean title, or null when no model could write one. */
    suspend fun title(turns: List<Turn>): String? = withContext(Dispatchers.IO) {
        val transcript = transcript(turns).ifBlank { return@withContext null }
        val model = settings.model()
        val raw = try {
            when {
                LocalModels.isLocal(model) -> null
                OllamaCloud.isOllama(model) -> settings.ollamaKey()?.let { ollamaTitle(it, model, transcript) }
                else -> settings.openRouterKey()?.let { openRouterTitle(it, model, transcript) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        raw?.let(::clean)
    }

    private fun transcript(turns: List<Turn>): String = turns.filter { it.text.isNotBlank() }.take(6).joinToString("\n\n") { t ->
        (if (t.role == Role.USER) "User: " else "Assistant: ") + t.text.take(1200)
    }.take(4000)

    private fun openRouterTitle(key: String, model: String, transcript: String): String? {
        val body = buildJsonObject {
            put("model", model)
            put("messages", messages(transcript))
            // Room for models that think before answering; the title itself is a few words.
            put("max_tokens", 400)
            put("temperature", 0.3)
            put("stream", false)
        }
        val root = post("https://openrouter.ai/api/v1/chat/completions", key, body.toString(), openRouter = true)
        val msg = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        return (msg?.get("content") as? JsonPrimitive)?.content
    }

    private suspend fun ollamaTitle(key: String, model: String, transcript: String): String? {
        val name = ollama.resolveName(OllamaCloud.wireName(model))
        val thinks = runCatching { ollama.info(name).thinking }.getOrDefault(OllamaCloud.knownThinking(name) || OllamaCloud.alwaysThinking(name))
        fun body(think: Boolean) = buildJsonObject {
            put("model", name)
            put("messages", messages(transcript))
            put("stream", false)
            // Titles never need thinking; switch it off where the model allows it.
            if (think) put("think", false)
            putJsonObject("options") { put("num_predict", 60); put("temperature", 0.3) }
        }.toString()
        val root = try {
            post(ollama.chatEndpoint, key, body(thinks), openRouter = false)
        } catch (e: IOException) {
            if (!thinks) throw e
            post(ollama.chatEndpoint, key, body(false), openRouter = false)
        }
        return ((root["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.content
    }

    private fun messages(transcript: String) = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put(
                "content",
                "You name chat conversations. Reply with a short title of 2 to 6 words that says what the " +
                    "conversation is about, in the conversation's language. No quotes, no emoji, no trailing period, " +
                    "nothing else.",
            )
        })
        add(buildJsonObject { put("role", "user"); put("content", "Conversation:\n\n$transcript\n\nTitle:") })
    }

    private fun post(url: String, key: String, body: String, openRouter: Boolean): JsonObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (openRouter) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/sebialex1/ai-agent-android")
                conn.setRequestProperty("X-Title", "Agent for Android")
            } else conn.setRequestProperty("User-Agent", OllamaCloud.USER_AGENT)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** First line, without "Title:", quotes, markdown and a trailing period; at most 60 characters. */
        fun clean(raw: String): String? {
            var t = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                .lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            t = t.replace(Regex("^(title|titlu|titre|titel)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                .trim('"', '\'', '*', '#', '`', ' ', '\u201c', '\u201d', '\u00ab', '\u00bb')
                .trimEnd('.', '!', ':', ';', ',')
                .trim()
            if (t.isEmpty()) return null
            if (t.length > 60) t = t.take(60).substringBeforeLast(' ', t.take(60)).trimEnd()
            return t
        }

        /**
         * Local fallback when no model writes a title: the first sentence of the request without
         * polite filler ("can you please ..."), capitalised, at most 6 words.
         */
        fun fallback(task: String): String {
            var t = task.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            t = t.replace(Regex("@\\S+"), "").trim()
            t = t.replace(Regex("^(hey|hi|hello|ok|okay|so)[,!.\\s]+", RegexOption.IGNORE_CASE), "")
            t = t.replace(Regex("^(can|could|would|will) you( please)?\\s+", RegexOption.IGNORE_CASE), "")
            t = t.replace(Regex("^(please|pls|i want you to|i need you to|i want to|help me)\\s+", RegexOption.IGNORE_CASE), "")
            t = t.split(Regex("(?<=[.!?])\\s")).first().trimEnd('.', '?', '!')
            val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }.take(6).joinToString(" ")
            val out = if (words.length > 40) words.take(40).trimEnd() else words
            return out.replaceFirstChar { it.uppercase() }.ifBlank { task.take(40) }
        }
    }
}
