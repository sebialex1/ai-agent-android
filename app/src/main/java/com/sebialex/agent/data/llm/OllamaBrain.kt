package com.sebialex.agent.data.llm

import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.agent.Thought
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent brain for Ollama Cloud on the native API (`POST https://ollama.com/api/chat`), as in
 * https://docs.ollama.com/cloud:
 *
 * - `model` is the exact name from `/api/tags` (resolved by [OllamaCloud.resolveName]);
 * - thinking is controlled with the native `think` field (`true`/`false` or a named level),
 *   and the trace arrives in `message.thinking`;
 * - tools use the native schema; tool results are sent back as `role: "tool"` with `tool_name`;
 * - the response streams as NDJSON (one JSON object per line, last one has `done: true`);
 * - errors are `{"error": "..."}` and are shown verbatim (a 403 is usually "this model requires
 *   a subscription" or an invalid key).
 *
 * The message history and tool definitions are shared with [OpenRouterBrain], so every provider
 * sees the same agent.
 */
class OllamaBrain(
    private val cloud: OllamaCloud,
    private val apiKey: String,
    private val model: String,
    /** Native `think` value for this request, or null to leave the model default. */
    private val think: suspend () -> JsonElement?,
) : AgentBrain {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision = withContext(Dispatchers.IO) {
        val name = cloud.resolveName(OllamaCloud.wireName(model))
        // If details cannot be read, still try with tools: most cloud models support them.
        var withTools = try {
            cloud.info(name).tools
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
        var thinkValue = try {
            think()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // One retry without the feature the model rejects ("does not support tools/thinking").
        var attempt = 0
        var c = ctx
        while (true) {
            try {
                return@withContext request(c, name, withTools, thinkValue, onReasoning)
            } catch (e: UnsupportedFeature) {
                if (attempt++ > 1) throw IOException(e.message)
                if (e.tools && withTools) withTools = false
                else if (e.thinking && thinkValue != null) thinkValue = null
                else throw IOException(e.message)
            } catch (e: IOException) {
                // A model without vision: once more without the pictures (the model is told).
                if (c.hasImages && BrainContext.isImageRejection(e.message)) c = c.withoutImages() else throw e
            }
        }
        throw IOException("unreachable")
    }

    private class UnsupportedFeature(message: String, val tools: Boolean, val thinking: Boolean) : Exception(message)

    private suspend fun request(
        ctx: BrainContext,
        name: String,
        withTools: Boolean,
        thinkValue: JsonElement?,
        onReasoning: (String) -> Unit,
    ): BrainDecision {
        val shared = OpenRouterBrain(apiKey = apiKey, model = name, provider = "Ollama Cloud", openRouterExtras = false, withTools = withTools)
        val body = buildJsonObject {
            put("model", name)
            put("messages", nativeMessages(shared.messages(ctx)))
            if (withTools) put("tools", OpenRouterBrain.toolsFor(ctx))
            if (thinkValue != null) put("think", thinkValue)
            put("stream", true)
        }.toString()

        val conn = URL(cloud.chatEndpoint).openConnection() as HttpURLConnection
        // Stop disconnects the stream at once instead of reading it to the end.
        return com.sebialex.agent.data.web.cancellableIo(close = { conn.disconnect() }) { stream(conn, ctx, name, body, onReasoning) }
    }

    private fun stream(conn: HttpURLConnection, ctx: BrainContext, name: String, body: String, onReasoning: (String) -> Unit): BrainDecision {
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/x-ndjson")
            conn.setRequestProperty("User-Agent", OllamaCloud.USER_AGENT)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val msg = OllamaCloud.errorMessage(text).orEmpty()
                if (code == 400 && msg.contains("does not support tools", ignoreCase = true)) throw UnsupportedFeature(msg, tools = true, thinking = false)
                if (code == 400 && msg.contains("does not support thinking", ignoreCase = true)) throw UnsupportedFeature(msg, tools = false, thinking = true)
                throw OllamaCloud.httpError(code, text, "talking to $name")
            }
            val content = StringBuilder()
            val thinking = StringBuilder()
            val calls = mutableListOf<Pair<String, String>>() // name to raw JSON args
            var doneReason: String? = null
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // Tolerate SSE framing too, in case a proxy rewrites the stream.
                    val data = line.trim().removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    chunk["error"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { err ->
                        val m = (err as? JsonPrimitive)?.content ?: ((err as? JsonObject)?.get("message") as? JsonPrimitive)?.content ?: "Ollama Cloud error"
                        if (m.contains("does not support tools", ignoreCase = true)) throw UnsupportedFeature(m, tools = true, thinking = false)
                        if (m.contains("does not support thinking", ignoreCase = true)) throw UnsupportedFeature(m, tools = false, thinking = true)
                        throw IOException(m)
                    }
                    val msg = chunk["message"] as? JsonObject
                    if (msg != null) {
                        str(msg["content"])?.let { content.append(it) }
                        str(msg["thinking"])?.takeIf { it.isNotEmpty() }?.let {
                            thinking.append(it)
                            runCatching { onReasoning(it) }
                        }
                        (msg["tool_calls"] as? JsonArray)?.forEach { el ->
                            val fn = (el as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
                            val n = str(fn["name"]).orEmpty()
                            val args = when (val a = fn["arguments"]) {
                                is JsonObject -> a.toString()
                                is JsonPrimitive -> a.content
                                else -> "{}"
                            }
                            if (n.isNotBlank()) calls += n to args
                        }
                    }
                    if ((chunk["done"] as? JsonPrimitive)?.content == "true") {
                        doneReason = str(chunk["done_reason"])
                        break
                    }
                }
            }
            val thought = thinking.toString().trim().takeIf { it.isNotEmpty() }?.let { Thought(it) }
            if (calls.isNotEmpty()) {
                val parsed = calls.mapIndexed { i, (n, a) ->
                    OpenRouterBrain.parseToolCall("call_${ctx.observations.size + i + 1}", n, a)
                }
                return BrainDecision.Act(parsed, thought)
            }
            val text = content.toString().trim()
            if (text.isEmpty()) throw IOException("the model returned an empty answer" + (doneReason?.let { " ($it)" } ?: ""))
            return BrainDecision.Answer(text, thought)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Converts the shared OpenAI-style history to Ollama's native shape: reasoning is replayed
     * as `thinking`, tool call arguments become JSON objects, and each tool result carries the
     * `tool_name` of the call it answers.
     */
    private fun nativeMessages(openAi: JsonArray): JsonArray {
        val names = HashMap<String, String>()
        return buildJsonArray {
            openAi.forEach { el ->
                val m = el as? JsonObject ?: return@forEach
                val role = str(m["role"]).orEmpty()
                // Content parts (text + image_url data URLs) become text plus native `images` (base64).
                val parts = m["content"] as? JsonArray
                val text = if (parts == null) str(m["content"]).orEmpty() else parts.mapNotNull { p ->
                    (p as? JsonObject)?.takeIf { str(it["type"]) == "text" }?.let { str(it["text"]) }
                }.joinToString("\n")
                val images = parts.orEmpty().mapNotNull { p ->
                    val o = p as? JsonObject ?: return@mapNotNull null
                    if (str(o["type"]) != "image_url") return@mapNotNull null
                    str((o["image_url"] as? JsonObject)?.get("url"))?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }
                }
                add(buildJsonObject {
                    put("role", role)
                    put("content", text)
                    if (images.isNotEmpty()) put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
                    if (role == "assistant") {
                        str(m["reasoning"])?.takeIf { it.isNotBlank() }?.let { put("thinking", it) }
                        (m["tool_calls"] as? JsonArray)?.let { tc ->
                            put("tool_calls", buildJsonArray {
                                tc.forEach { c ->
                                    val o = c as? JsonObject ?: return@forEach
                                    val fn = o["function"] as? JsonObject ?: return@forEach
                                    val n = str(fn["name"]).orEmpty()
                                    str(o["id"])?.let { names[it] = n }
                                    val args = runCatching { json.parseToJsonElement(str(fn["arguments"]) ?: "{}") as? JsonObject }.getOrNull()
                                        ?: JsonObject(emptyMap())
                                    add(buildJsonObject {
                                        put("function", buildJsonObject {
                                            put("name", n)
                                            put("arguments", args)
                                        })
                                    })
                                }
                            })
                        }
                    }
                    if (role == "tool") {
                        str(m["tool_call_id"])?.let { id -> names[id]?.let { put("tool_name", it) } }
                    }
                })
            }
        }
    }

    private fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
}
