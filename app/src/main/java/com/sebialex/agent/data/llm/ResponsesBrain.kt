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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM brain speaking OpenAI's Responses API (`POST <root>/responses`) with tool calling, for
 * custom providers that serve it. The exchange is built from the same OpenAI-style messages as
 * the chat brain ([OpenRouterBrain.messages]) and converted to Responses input items; streamed
 * events are reassembled into one decision, like the chat brain reassembles chat deltas.
 *
 * Providers that do not know `/responses` answer 404/405 (or OpenAI's "Unrecognized request
 * URL"): the first such answer permanently switches this brain to its [fallback] chat
 * completions brain, so a Responses-first provider keeps working without a second setup step.
 */
class ResponsesBrain(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String,
    /**
     * Reasoning fields for this request, in the chat-completions shape the rules build
     * (`reasoning`/`reasoning_effort`); converted here to the Responses `reasoning.effort`.
     */
    private val reasoningFields: suspend () -> JsonObject = { JsonObject(emptyMap()) },
    /** Provider name used in error messages. */
    private val provider: String = "Responses API",
    /** Offer the agent's tools. Off for models whose provider reports no tool support. */
    private val withTools: Boolean = true,
    /** The chat-completions brain of the same provider, used when `/responses` does not exist. */
    private val fallback: AgentBrain? = null,
) : AgentBrain {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var useFallback = false

    /** The chat-shaped message builder, reused for its message list (never does any networking). */
    private val chatShape = OpenRouterBrain(
        apiKey = apiKey, model = model, endpoint = endpoint, provider = provider,
        openRouterExtras = false, withTools = withTools, replayReasoning = false,
    )

    private class EndpointUnsupported(message: String) : IOException(message)

    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
        val fb = fallback
        if (useFallback && fb != null) return fb.next(ctx, onReasoning)
        return try {
            request(ctx, onReasoning)
        } catch (e: EndpointUnsupported) {
            if (fb != null) {
                useFallback = true
                fb.next(ctx, onReasoning)
            } else {
                throw IOException("this provider has no Responses API; set it to Chat Completions in Settings > Models")
            }
        } catch (e: IOException) {
            // A model without image input rejects the whole request: answer once more without the
            // pictures, and the model is told it could not see them.
            if (ctx.hasImages && BrainContext.isImageRejection(e.message)) request(ctx.withoutImages(), onReasoning) else throw e
        }
    }

    private suspend fun request(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision = withContext(Dispatchers.IO) {
        // A metadata failure must never block the chat: send no reasoning in that case.
        val reasoning = try {
            reasoningFields()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        val messages = chatShape.messages(ctx)
        val body = buildJsonObject {
            put("model", model)
            systemOf(messages)?.let { put("instructions", it) }
            put("input", inputItems(messages))
            if (withTools) {
                put("tools", toolsFor(ctx))
                put("tool_choice", "auto")
                put("parallel_tool_calls", true)
            }
            reasoningEffort(reasoning)?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
            put("stream", true)
        }.toString()
        val answer = postStreaming(body, onReasoning)
        val calls = answer.calls.values.map { OpenRouterBrain.parseToolCall(it.id, it.name, it.args.toString().ifBlank { "{}" }) }
        val thought = answer.reasoning.toString().trim().takeIf { it.isNotEmpty() }?.let { Thought(it) }
        if (calls.isNotEmpty()) return@withContext BrainDecision.Act(calls, thought)
        val text = answer.text.toString().trim()
        if (text.isEmpty()) {
            throw IOException("$provider returned an empty answer" + (answer.finishReason?.let { " ($it)" } ?: ""))
        }
        BrainDecision.Answer(text, thought)
    }

    private fun systemOf(messages: JsonArray): String? = messages.firstOrNull()?.let { m ->
        (m as? JsonObject)?.takeIf { (it["role"] as? JsonPrimitive)?.content == "system" }
            ?.let { (it["content"] as? JsonPrimitive)?.takeIf { c -> c.isString }?.content }
    }

    /** OpenAI-style chat messages converted to Responses input items. */
    private fun inputItems(messages: JsonArray): JsonArray = JsonArray(messages.flatMap { el ->
        val m = el as? JsonObject ?: return@flatMap emptyList()
        val role = (m["role"] as? JsonPrimitive)?.content.orEmpty()
        val toolCalls = m["tool_calls"] as? JsonArray
        when {
            role == "system" -> emptyList()
            role == "tool" -> listOf(
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", (m["tool_call_id"] as? JsonPrimitive)?.content.orEmpty())
                    put("output", (m["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty())
                },
            )
            toolCalls != null -> {
                val items = mutableListOf<JsonObject>()
                (m["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let {
                    items += messageItem(role, JsonPrimitive(it))
                }
                toolCalls.forEach { c ->
                    val o = c as? JsonObject ?: return@forEach
                    val fn = o["function"] as? JsonObject ?: return@forEach
                    items += buildJsonObject {
                        put("type", "function_call")
                        put("call_id", (o["id"] as? JsonPrimitive)?.content.orEmpty())
                        put("name", (fn["name"] as? JsonPrimitive)?.content.orEmpty())
                        put("arguments", (fn["arguments"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "{}" })
                    }
                }
                items
            }
            else -> listOf(messageItem(role, m["content"]))
        }
    })

    /** One message item; [content] is a string or an array of parts (text, pictures). */
    private fun messageItem(role: String, content: JsonElement?): JsonObject {
        val textPart = if (role == "assistant") "output_text" else "input_text"
        fun text(t: String) = buildJsonObject { put("type", textPart); put("text", t) }
        val parts = when (content) {
            is JsonPrimitive -> listOf(text(if (content.isString) content.content else ""))
            is JsonArray -> content.flatMap { part ->
                val o = part as? JsonObject ?: return@flatMap emptyList<JsonObject>()
                when ((o["type"] as? JsonPrimitive)?.content) {
                    "image_url" -> listOf(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", ((o["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content.orEmpty())
                        put("detail", "auto")
                    })
                    else -> listOf(text((o["text"] as? JsonPrimitive)?.content.orEmpty()))
                }
            }
            else -> emptyList()
        }
        return buildJsonObject {
            put("role", role)
            putJsonArray("content") { parts.forEach { add(it) } }
        }
    }

    /** Chat-completions tool schemas converted to the Responses function shape. */
    private fun toolsFor(ctx: BrainContext): JsonArray {
        val out = ArrayList<JsonObject>()
        OpenRouterBrain.toolsFor(ctx).forEach { el ->
            val t = el as? JsonObject ?: return@forEach
            val fn = t["function"] as? JsonObject ?: return@forEach
            out += buildJsonObject {
                put("type", "function")
                put("name", (fn["name"] as? JsonPrimitive)?.content.orEmpty())
                (fn["description"] as? JsonPrimitive)?.let { put("description", it) }
                fn["parameters"]?.let { put("parameters", it) }
                put("strict", false)
            }
        }
        return JsonArray(out)
    }

    /** The rules build chat-style reasoning fields; the Responses API takes `reasoning.effort` only. */
    private fun reasoningEffort(fields: JsonObject): String? {
        val block = fields["reasoning"] as? JsonObject
        val fromBlock = (block?.get("effort") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val fromField = (fields["reasoning_effort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return (fromBlock ?: fromField)?.takeIf { it != "none" }
    }

    private suspend fun postStreaming(body: String, onReasoning: (String) -> Unit): Answer {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        return com.sebialex.agent.data.web.cancellableIo(close = { conn.disconnect() }) { readStream(conn, body, onReasoning) }
    }

    private class Call {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private class Answer {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        /** By item id; [Call.id] carries the provider's call_id. */
        val calls = LinkedHashMap<String, Call>()
        var finishReason: String? = null
    }

    private fun readStream(conn: HttpURLConnection, body: String, onReasoning: (String) -> Unit): Answer {
        val answer = Answer()
        conn.outputStream.use { it.write(body.toByteArray()) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 404 || code == 405 || "Unrecognized request URL" in text) {
                    throw EndpointUnsupported("HTTP $code: no Responses API at this endpoint")
                }
                throw httpError(code, text)
            }
            conn.inputStream.bufferedReader().use { reader ->
                val first = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (first.isEmpty() && line.trimStart().startsWith("{")) {
                        // Not an event stream: the whole body is one JSON document.
                        first.append(line).append('\n').append(reader.readText())
                        return nonStreaming(first.toString(), answer)
                    }
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    (chunk["error"] as? JsonObject)?.let { err ->
                        throw IOException((err["message"] as? JsonPrimitive)?.content ?: "$provider error")
                    }
                    event(chunk, answer, onReasoning)
                }
            }
            return answer
        } finally {
            conn.disconnect()
        }
    }

    /** One SSE event; deltas fill in the answer, the final state arrives with the response events. */
    private fun event(chunk: JsonObject, answer: Answer, onReasoning: (String) -> Unit) {
        when ((chunk["type"] as? JsonPrimitive)?.content) {
            "response.output_text.delta" -> (chunk["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.let { answer.text.append(it.content) }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                (chunk["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
                    answer.reasoning.append(it.content)
                    runCatching { onReasoning(it.content) }
                }
            }
            "response.output_item.added" -> (chunk["item"] as? JsonObject)?.let { registerCall(it, answer) }
            "response.function_call_arguments.delta" -> {
                val call = answer.calls[(chunk["item_id"] as? JsonPrimitive)?.content.orEmpty()] ?: return
                (chunk["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.let { call.args.append(it.content) }
            }
            "response.output_item.done" -> (chunk["item"] as? JsonObject)?.let { consume(it, answer, onReasoning) }
            "response.completed", "response.incomplete", "response.failed" -> {
                (chunk["response"] as? JsonObject)?.let { r ->
                    (r["output"] as? JsonArray)?.forEach { el -> (el as? JsonObject)?.let { consume(it, answer, onReasoning) } }
                    (r["error"] as? JsonObject)?.let { err ->
                        throw IOException((err["message"] as? JsonPrimitive)?.content ?: "$provider error")
                    }
                    (r["status"] as? JsonPrimitive)?.takeIf { it.isString }?.let { answer.finishReason = it.content }
                }
            }
        }
    }

    /** Registers a streamed function_call item so its argument deltas have somewhere to land. */
    private fun registerCall(item: JsonObject, answer: Answer) {
        if ((item["type"] as? JsonPrimitive)?.content != "function_call") return
        val id = (item["id"] as? JsonPrimitive)?.content.orEmpty()
        val call = answer.calls.getOrPut(id) { Call() }
        call.id = (item["call_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id
        (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { call.name = it }
        (item["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.let { call.args.append(it) }
    }

    /** A done output item (stream or final response object) into [answer]. */
    private fun consume(item: JsonObject, answer: Answer, onReasoning: (String) -> Unit) {
        when ((item["type"] as? JsonPrimitive)?.content) {
            "message" -> (item["content"] as? JsonArray).orEmpty().forEach { part ->
                val o = part as? JsonObject ?: return@forEach
                if ((o["type"] as? JsonPrimitive)?.content == "output_text") {
                    ((o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()).let { answer.text.append(it) }
                }
            }
            "reasoning" -> (item["summary"] as? JsonArray).orEmpty().forEach { part ->
                ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
                    answer.reasoning.append(it)
                    runCatching { onReasoning(it) }
                }
            }
            else -> registerCall(item, answer)
        }
    }

    private fun nonStreaming(text: String, answer: Answer): Answer {
        val root = json.parseToJsonElement(text).jsonObject
        (root["error"] as? JsonObject)?.let { err ->
            throw IOException((err["message"] as? JsonPrimitive)?.content ?: "$provider error")
        }
        (root["output"] as? JsonArray)?.forEach { el -> (el as? JsonObject)?.let { consume(it, answer) {} } }
        (root["status"] as? JsonPrimitive)?.takeIf { it.isString }?.let { answer.finishReason = it.content }
        return answer
    }

    private fun httpError(code: Int, text: String): IOException {
        val detail = runCatching {
            ((json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
        }.getOrNull()
        return IOException("HTTP $code" + if (!detail.isNullOrBlank()) ": $detail" else "")
    }
}
