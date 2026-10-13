package com.sebialex.agent.data.llm

import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.agent.Observation
import com.sebialex.agent.domain.agent.Thought
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LLM brain backed by OpenRouter's OpenAI-compatible chat completions endpoint
 * (`POST https://openrouter.ai/api/v1/chat/completions`) with tool calling.
 *
 * Every request replays the real exchange for the current turn: the assistant message with its
 * `tool_calls`, followed by one `role: "tool"` message per call carrying the actual tool output.
 * Parallel tool calls from one response are executed in order and replayed as one batch.
 * The model id comes from settings (default "openrouter/auto").
 */
class OpenRouterBrain(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
    /**
     * Reasoning fields for this request (`reasoning`, `reasoning_effort` or `include_reasoning`),
     * built from the model's live metadata and the user's per-model setting. Empty when the
     * model has no controllable reasoning.
     */
    private val reasoningFields: suspend () -> JsonObject = { JsonObject(emptyMap()) },
    /** Provider name used in error messages. */
    private val provider: String = "OpenRouter",
    /**
     * OpenRouter-only request extras: `tool_choice`, `parallel_tool_calls` and the attribution
     * headers. Off for other OpenAI-compatible providers (Ollama Cloud) that do not take them.
     */
    private val openRouterExtras: Boolean = true,
    /** Offer the agent's tools. Off for models whose provider reports no tool support. */
    private val withTools: Boolean = true,
    /** Send the model's earlier reasoning back with its tool calls (OpenRouter, Ollama's native conversion). */
    private val replayReasoning: Boolean = true,
) : AgentBrain {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision = try {
        request(ctx, onReasoning)
    } catch (e: IOException) {
        // A model without image input rejects the whole request: answer once more without the
        // pictures, and the model is told it could not see them.
        if (ctx.hasImages && BrainContext.isImageRejection(e.message)) request(ctx.withoutImages(), onReasoning) else throw e
    }

    private suspend fun request(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision = withContext(Dispatchers.IO) {
        // A metadata failure must never block the chat: send no reasoning fields in that case.
        val reasoning = try {
            reasoningFields()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        val body = buildJsonObject {
            put("model", model)
            put("messages", messages(ctx))
            if (withTools) {
                put("tools", toolsFor(ctx.memoryTool))
                if (openRouterExtras) {
                    put("tool_choice", "auto")
                    put("parallel_tool_calls", true)
                }
            }
            reasoning.forEach { (k, v) -> put(k, v) }
            // Streamed so reasoning can be shown live; the full message is reassembled below.
            put("stream", true)
        }.toString()
        val choice = postStreaming(body, onReasoning)
        val message = choice["message"] as? JsonObject ?: throw IOException("$provider returned no message")

        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            parseCall(o, fallbackId = "call_${ctx.observations.size + i + 1}")
        }
        val thought = thoughtOf(message)
        if (calls.isNotEmpty()) return@withContext BrainDecision.Act(calls, thought)

        val text = contentText(message["content"]).trim()
        if (text.isEmpty()) {
            val reason = (choice["finish_reason"] as? JsonPrimitive)?.content
            throw IOException("the model returned an empty answer" + if (reason != null) " ($reason)" else "")
        }
        BrainDecision.Answer(text, thought)
    }

    /**
     * Reasoning from `message.reasoning` (or its alias `reasoning_content`); when only
     * `reasoning_details` is present, its text and summary parts are joined. Encrypted parts
     * have no readable text but are still kept for replay.
     */
    private fun thoughtOf(message: JsonObject): Thought? {
        val details = message["reasoning_details"] as? JsonArray
        val plain = listOf("reasoning", "reasoning_content").firstNotNullOfOrNull { k ->
            (message[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        }
        val fromDetails = details.orEmpty().mapNotNull { d ->
            val o = d as? JsonObject ?: return@mapNotNull null
            listOf("text", "summary").firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        }.joinToString("\n").takeIf { it.isNotBlank() }
        val text = (plain ?: fromDetails)?.trim().orEmpty()
        val raw = details?.takeIf { it.isNotEmpty() }?.toString()
        if (text.isEmpty() && raw == null) return null
        return Thought(text, raw)
    }

    private fun parseCall(o: JsonObject, fallbackId: String): ToolCall {
        val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallbackId
        val fn = o["function"] as? JsonObject
        val name = (fn?.get("name") as? JsonPrimitive)?.content.orEmpty()
        val rawArgs = (fn?.get("arguments") as? JsonPrimitive)?.content.orEmpty().ifBlank { "{}" }
        return parseToolCall(id, name, rawArgs)
    }

    /** Content may be a plain string or an array of content parts. */
    private fun contentText(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> if (el.isString) el.content else ""
        is JsonArray -> el.joinToString("") { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
        }
        else -> ""
    }

    /** OpenAI-style message list for this request. Also used (converted) by the native Ollama brain. */
    internal fun messages(ctx: BrainContext): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt(ctx))
        })
        // Earlier turns of this conversation only. Each conversation has its own context.
        ctx.history.filter { it.complete || it.role == Role.USER }.takeLast(MAX_HISTORY_TURNS).forEach { t ->
            if (t.text.isBlank()) return@forEach
            add(buildJsonObject {
                put("role", if (t.role == Role.USER) "user" else "assistant")
                put("content", if (t.role == Role.ASSISTANT) t.text + actionsNote(t) else t.text)
            })
        }
        add(buildJsonObject {
            put("role", "user")
            when {
                ctx.images.isNotEmpty() -> put("content", contentWithImages(ctx.task, ctx.images))
                ctx.imagesDropped -> put("content", ctx.task + "\n\n" + BrainContext.IMAGES_DROPPED_NOTE)
                else -> put("content", ctx.task)
            }
        })
        ctx.observations.groupBy { it.batch }.toSortedMap().forEach { (batch, group) -> addBatch(group, ctx.thoughts[batch]) }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addBatch(group: List<Observation>, thought: Thought?) {
        add(buildJsonObject {
            put("role", "assistant")
            put("content", "")
            // Replay the model's own reasoning for this step so it keeps its chain of thought.
            // Off for custom OpenAI-compatible APIs, which may reject unknown message fields
            // (DeepSeek, for one, refuses reasoning sent back to it).
            if (replayReasoning && thought != null && thought.text.isNotBlank()) put("reasoning", thought.text)
            if (replayReasoning) thought?.details?.let { raw ->
                runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()?.let { put("reasoning_details", it) }
            }
            putJsonArray("tool_calls") {
                group.forEach { o ->
                    add(buildJsonObject {
                        put("id", o.call.callId)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", toolName(o.call))
                            put("arguments", arguments(o.call))
                        }
                    })
                }
            }
        })
        group.forEach { o ->
            add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", o.call.callId)
                val body = o.result.output.ifEmpty { if (o.result.ok) "(no output)" else "(failed with no output)" }
                val code = o.result.exitCode?.let { "exit code $it\n" }.orEmpty()
                put("content", (if (o.result.ok) "" else "ERROR: ") + code + body.take(MAX_TOOL_OUTPUT))
            })
        }
        // Tool messages carry text only, so pictures from view_image follow as one user message.
        val images = group.flatMap { it.result.images }
        if (images.isNotEmpty()) add(buildJsonObject {
            put("role", "user")
            put("content", contentWithImages("[view_image] " + images.joinToString(", ") { it.label }, images))
        })
    }

    /** OpenAI-style content parts: the text, then one `image_url` part (a data URL) per picture. */
    private fun contentWithImages(text: String, images: List<com.sebialex.agent.domain.model.ImageInput>): JsonArray = buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", text) })
        images.forEach { img ->
            add(buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", img.dataUrl) }
            })
        }
    }

    /** Short record of what an earlier answer actually did, so follow-ups have real context. */
    private fun actionsNote(t: com.sebialex.agent.domain.model.Turn): String {
        val done = t.steps.filter { it.status == StepStatus.DONE && it.tool != "permission" && it.tool != "reasoning" }
        if (done.isEmpty() && t.attachments.isEmpty() && t.artifacts.isEmpty()) return ""
        val lines = done.takeLast(12).map { "- ${it.tool}: ${it.payload.take(160)}" } +
            t.attachments.map { "- file: ${it.path}" } +
            t.artifacts.map { "- attached: ${it.path}" }
        return "\n\n[actions taken in this turn]\n" + lines.joinToString("\n")
    }

    private fun systemPrompt(ctx: BrainContext): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val files = ctx.workspaceFiles.take(200)
        val noTools = if (withTools) "" else "\nTools are not available with this model: answer directly and do not claim to run anything.\n"
        val memory = if (ctx.memoryTool && withTools) "\n" + MEMORY_PROMPT else ""
        return SYSTEM_PROMPT + noTools + memory + "\nToday is $date.\n" +
            if (files.isEmpty()) "The workspace is empty." else "Workspace files:\n" + files.joinToString("\n")
    }

    /**
     * Sends the request with `stream: true` and reads OpenRouter's server-sent events. Content,
     * reasoning, `reasoning_details` and `tool_calls` deltas are accumulated into one choice
     * object of the same shape as a non-streaming response (`{message, finish_reason}`), so the
     * parsing above is shared. Reasoning deltas are forwarded to [onReasoning] as they arrive.
     * A plain JSON body (some errors, or providers that ignore `stream`) is handled too.
     */
    private fun postStreaming(body: String, onReasoning: (String) -> Unit): JsonObject {
        val conn = open(body, accept = "text/event-stream")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw httpError(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            val acc = StreamAccumulator()
            conn.inputStream.bufferedReader().use { reader ->
                val first = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (acc.events == 0 && first.isEmpty() && line.trimStart().startsWith("{")) {
                        // Not an event stream: the whole body is one JSON document.
                        first.append(line).append('\n').append(reader.readText())
                        return nonStreamingChoice(first.toString())
                    }
                    if (line.isEmpty() || line.startsWith(":")) continue // keep-alive comments
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    (chunk["error"] as? JsonObject)?.let { err ->
                        throw IOException((err["message"] as? JsonPrimitive)?.content ?: "$provider error")
                    }
                    acc.add(chunk, onReasoning)
                }
            }
            return acc.choice()
        } finally {
            conn.disconnect()
        }
    }

    private fun nonStreamingChoice(text: String): JsonObject {
        val root = json.parseToJsonElement(text).jsonObject
        (root["error"] as? JsonObject)?.let { err ->
            throw IOException((err["message"] as? JsonPrimitive)?.content ?: "$provider error")
        }
        return (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw IOException("$provider returned no choices")
    }

    /** Reassembles streamed deltas into a single non-streaming style choice. */
    private class StreamAccumulator {
        var events = 0
        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private var finishReason: String? = null
        private class Call(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
        private val calls = sortedMapOf<Int, Call>()
        /** reasoning_details parts, merged per (index or position, type). */
        private val details = linkedMapOf<String, MutableMap<String, JsonElement>>()

        fun add(chunk: JsonObject, onReasoning: (String) -> Unit) {
            events++
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
            (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { finishReason = it }
            val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return
            str(delta["content"])?.let { content.append(it) }
            val plain = str(delta["reasoning"]) ?: str(delta["reasoning_content"])
            var detailText = ""
            (delta["reasoning_details"] as? JsonArray)?.forEachIndexed { pos, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val type = str(o["type"]).orEmpty()
                val key = ((o["index"] as? JsonPrimitive)?.content ?: "p$pos") + ":" + type
                val merged = details.getOrPut(key) { linkedMapOf() }
                o.forEach { (k, v) ->
                    val prev = merged[k]
                    merged[k] = if (k in CONCAT && prev is JsonPrimitive && prev.isString && v is JsonPrimitive && v.isString) {
                        JsonPrimitive(prev.content + v.content)
                    } else v
                }
                listOf("text", "summary").firstNotNullOfOrNull { str(o[it]) }?.let { detailText += it }
            }
            // OpenRouter usually sends both `reasoning` and `reasoning_details`; show it once.
            val live = plain?.takeIf { it.isNotEmpty() } ?: detailText.takeIf { it.isNotEmpty() }
            if (live != null) {
                reasoning.append(live)
                runCatching { onReasoning(live) }
            }
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val index = (o["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: pos
                val call = calls.getOrPut(index) { Call() }
                str(o["id"])?.takeIf { it.isNotBlank() }?.let { call.id = it }
                val fn = o["function"] as? JsonObject
                str(fn?.get("name"))?.takeIf { it.isNotBlank() }?.let { call.name = it }
                str(fn?.get("arguments"))?.let { call.args.append(it) }
            }
        }

        fun choice(): JsonObject = buildJsonObject {
            putJsonObject("message") {
                put("role", "assistant")
                put("content", content.toString())
                if (reasoning.isNotEmpty()) put("reasoning", reasoning.toString())
                if (details.isNotEmpty()) put("reasoning_details", JsonArray(details.values.map { JsonObject(it) }))
                if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                    calls.values.forEach { c ->
                        add(buildJsonObject {
                            if (c.id.isNotBlank()) put("id", c.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", c.name)
                                put("arguments", c.args.toString())
                            }
                        })
                    }
                }
            }
            finishReason?.let { put("finish_reason", it) }
        }

        private fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it.isString }?.content

        private companion object {
            /** Fields that arrive in pieces and must be concatenated. */
            val CONCAT = setOf("text", "summary", "data")
        }
    }

    private fun open(body: String, accept: String): HttpURLConnection {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", accept)
        if (openRouterExtras) {
            conn.setRequestProperty("HTTP-Referer", "https://github.com/sebialex1/ai-agent-android")
            conn.setRequestProperty("X-Title", "Agent for Android")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn
    }

    private fun httpError(code: Int, text: String): IOException {
        val detail = runCatching {
            ((json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
        }.getOrNull()
        return IOException("HTTP $code" + if (!detail.isNullOrBlank()) ": $detail" else "")
    }

    companion object {

        private val ARGS_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Wire name of a tool call. */
        fun toolName(c: ToolCall) = when (c) {
            is ToolCall.WebSearch -> "web_search"
            is ToolCall.WebFetch -> "web_fetch"
            is ToolCall.Shell -> "shell"
            is ToolCall.ReadFile -> "read_file"
            is ToolCall.WriteFile -> "write_file"
            is ToolCall.Python -> "python"
            is ToolCall.Memory -> MEMORY_TOOL_NAME
            is ToolCall.ListFiles -> "list_files"
            is ToolCall.SearchFiles -> "search_files"
            is ToolCall.EditFile -> "edit_file"
            is ToolCall.MoveFile -> "move_file"
            is ToolCall.DeleteFile -> "delete_file"
            is ToolCall.DownloadFile -> "download_file"
            is ToolCall.HttpRequest -> "http_request"
            is ToolCall.DeviceInfo -> "device_info"
            is ToolCall.CheckTools -> "check_tools"
            is ToolCall.AttachFiles -> "attach_files"
            is ToolCall.CopyFile -> "copy_file"
            is ToolCall.FileInfo -> "file_info"
            is ToolCall.ZipFiles -> "zip_files"
            is ToolCall.UnzipFile -> "unzip_file"
            is ToolCall.GenerateImage -> "generate_image"
            is ToolCall.ViewImage -> "view_image"
            is ToolCall.Invalid -> c.name.ifBlank { "unknown" }
        }

        /** Wire JSON arguments of a tool call. */
        fun arguments(c: ToolCall): String = when (c) {
            is ToolCall.WebSearch -> buildJsonObject { put("query", c.query) }.toString()
            is ToolCall.WebFetch -> buildJsonObject { put("url", c.url) }.toString()
            is ToolCall.Shell -> buildJsonObject { put("command", c.command) }.toString()
            is ToolCall.ReadFile -> buildJsonObject { put("path", c.path) }.toString()
            is ToolCall.WriteFile -> buildJsonObject { put("path", c.path); put("content", c.content) }.toString()
            is ToolCall.Python -> buildJsonObject {
                put("code", c.code)
                if (c.packages.isNotEmpty()) putJsonArray("packages") { c.packages.forEach { add(it) } }
                c.filename?.let { put("filename", it) }
            }.toString()
            is ToolCall.Memory -> buildJsonObject {
                put("action", c.action)
                c.conversationId?.let { put("conversation_id", it) }
                if (c.offset > 0) put("offset", c.offset)
                c.query?.let { put("query", it) }
            }.toString()
            is ToolCall.ListFiles -> buildJsonObject { put("path", c.path); if (c.recursive) put("recursive", true) }.toString()
            is ToolCall.SearchFiles -> buildJsonObject { put("query", c.query); put("path", c.path); if (c.regex) put("regex", true) }.toString()
            is ToolCall.EditFile -> buildJsonObject {
                put("path", c.path); put("old_text", c.oldText); put("new_text", c.newText)
                if (c.replaceAll) put("replace_all", true)
            }.toString()
            is ToolCall.MoveFile -> buildJsonObject { put("from", c.from); put("to", c.to) }.toString()
            is ToolCall.DeleteFile -> buildJsonObject { put("path", c.path) }.toString()
            is ToolCall.DownloadFile -> buildJsonObject { put("url", c.url); c.path?.let { put("path", it) } }.toString()
            is ToolCall.HttpRequest -> buildJsonObject {
                put("method", c.method); put("url", c.url)
                if (c.headers.isNotEmpty()) putJsonObject("headers") { c.headers.forEach { (k, v) -> put(k, v) } }
                c.body?.let { put("body", it) }
            }.toString()
            is ToolCall.DeviceInfo -> "{}"
            is ToolCall.CopyFile -> buildJsonObject { put("from", c.from); put("to", c.to) }.toString()
            is ToolCall.FileInfo -> buildJsonObject { put("path", c.path) }.toString()
            is ToolCall.ZipFiles -> buildJsonObject { putJsonArray("paths") { c.paths.forEach { add(it) } }; put("output", c.output) }.toString()
            is ToolCall.UnzipFile -> buildJsonObject { put("path", c.path); c.to?.let { put("to", it) } }.toString()
            is ToolCall.GenerateImage -> buildJsonObject {
                put("prompt", c.prompt)
                c.filename?.let { put("filename", it) }
                c.aspectRatio?.let { put("aspect_ratio", it) }
            }.toString()
            is ToolCall.AttachFiles -> buildJsonObject {
                putJsonArray("files") {
                    c.files.forEach { f -> add(buildJsonObject { put("path", f.path); f.name?.let { put("name", it) } }) }
                }
            }.toString()
            is ToolCall.CheckTools -> buildJsonObject { putJsonArray("tools") { c.tools.forEach { add(it) } } }.toString()
            is ToolCall.ViewImage -> buildJsonObject { put("path", c.path) }.toString()
            is ToolCall.Invalid -> c.arguments
        }

        /**
         * Turns one tool call (name plus raw JSON arguments) into a [ToolCall], or a
         * [ToolCall.Invalid] that explains what was wrong. Shared by every brain.
         */
        fun parseToolCall(id: String, name: String, rawArgs: String): ToolCall {
            val args = runCatching { ARGS_JSON.parseToJsonElement(rawArgs.ifBlank { "{}" }).jsonObject }.getOrNull()
                ?: return ToolCall.Invalid(id, name, rawArgs, "Arguments were not valid JSON: $rawArgs")
            fun arg(k: String) = (args[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            fun flag(k: String) = arg(k)?.trim()?.lowercase() in setOf("true", "1", "yes")
            fun missing(k: String) = ToolCall.Invalid(id, name, rawArgs, "Missing required argument \"$k\" for $name")
            return when (name) {
                "web_search" -> arg("query")?.takeIf { it.isNotBlank() }?.let { ToolCall.WebSearch(id, it) } ?: missing("query")
                "web_fetch" -> arg("url")?.takeIf { it.isNotBlank() }?.let { ToolCall.WebFetch(id, it) } ?: missing("url")
                "shell" -> arg("command")?.takeIf { it.isNotBlank() }?.let { ToolCall.Shell(id, it) } ?: missing("command")
                "read_file" -> arg("path")?.takeIf { it.isNotBlank() }?.let { ToolCall.ReadFile(id, it) } ?: missing("path")
                "write_file" -> {
                    val path = arg("path")?.takeIf { it.isNotBlank() } ?: return missing("path")
                    val content = arg("content") ?: return missing("content")
                    ToolCall.WriteFile(id, path, content)
                }
                "python" -> {
                    val code = arg("code")?.takeIf { it.isNotBlank() } ?: return missing("code")
                    // packages may come as a JSON array or as one space/comma separated string.
                    val packages = when (val p = args["packages"]) {
                        is JsonArray -> p.mapNotNull { (it as? JsonPrimitive)?.content }
                        is JsonPrimitive -> if (p.isString) p.content.split(Regex("[\\s,]+")) else emptyList()
                        else -> emptyList()
                    }.map { it.trim() }.filter { it.isNotEmpty() }
                    ToolCall.Python(id, code, packages, arg("filename"))
                }
                MEMORY_TOOL_NAME -> {
                    val action = arg("action")?.trim()?.takeIf { it.isNotEmpty() } ?: return missing("action")
                    // offset may arrive as a number or a numeric string.
                    val offset = arg("offset")?.trim()?.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
                    ToolCall.Memory(id, action, arg("conversation_id")?.takeIf { it.isNotBlank() }, offset, arg("query")?.takeIf { it.isNotBlank() })
                }
                "list_files" -> ToolCall.ListFiles(id, arg("path")?.takeIf { it.isNotBlank() } ?: "/", flag("recursive"))
                "search_files" -> arg("query")?.takeIf { it.isNotEmpty() }?.let {
                    ToolCall.SearchFiles(id, it, arg("path")?.takeIf { p -> p.isNotBlank() } ?: "/", flag("regex"))
                } ?: missing("query")
                "edit_file" -> {
                    val path = arg("path")?.takeIf { it.isNotBlank() } ?: return missing("path")
                    val old = arg("old_text") ?: return missing("old_text")
                    val new = arg("new_text") ?: return missing("new_text")
                    ToolCall.EditFile(id, path, old, new, flag("replace_all"))
                }
                "move_file" -> {
                    val from = arg("from")?.takeIf { it.isNotBlank() } ?: return missing("from")
                    val to = arg("to")?.takeIf { it.isNotBlank() } ?: return missing("to")
                    ToolCall.MoveFile(id, from, to)
                }
                "delete_file" -> arg("path")?.takeIf { it.isNotBlank() }?.let { ToolCall.DeleteFile(id, it) } ?: missing("path")
                "download_file" -> arg("url")?.takeIf { it.isNotBlank() }?.let { ToolCall.DownloadFile(id, it, arg("path")?.takeIf { p -> p.isNotBlank() }) } ?: missing("url")
                "http_request" -> {
                    val url = arg("url")?.takeIf { it.isNotBlank() } ?: return missing("url")
                    val headers = when (val h = args["headers"]) {
                        is JsonObject -> h.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
                        is JsonPrimitive -> runCatching { ARGS_JSON.parseToJsonElement(h.content).jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() } }.getOrDefault(emptyMap())
                        else -> emptyMap()
                    }
                    val body = when (val b = args["body"]) {
                        null, is kotlinx.serialization.json.JsonNull -> null
                        is JsonPrimitive -> b.content
                        else -> b.toString()
                    }
                    ToolCall.HttpRequest(id, (arg("method") ?: "GET").trim().uppercase(), url, headers, body)
                }
                "device_info" -> ToolCall.DeviceInfo(id)
                "copy_file" -> {
                    val from = arg("from")?.takeIf { it.isNotBlank() } ?: return missing("from")
                    val to = arg("to")?.takeIf { it.isNotBlank() } ?: return missing("to")
                    ToolCall.CopyFile(id, from, to)
                }
                "file_info" -> arg("path")?.takeIf { it.isNotBlank() }?.let { ToolCall.FileInfo(id, it) } ?: missing("path")
                "zip_files" -> {
                    val paths = stringList(args["paths"]).ifEmpty { listOfNotNull(arg("path")?.takeIf { it.isNotBlank() }) }
                    if (paths.isEmpty()) return missing("paths")
                    val output = arg("output")?.takeIf { it.isNotBlank() } ?: return missing("output")
                    ToolCall.ZipFiles(id, paths, output)
                }
                "unzip_file" -> arg("path")?.takeIf { it.isNotBlank() }?.let { ToolCall.UnzipFile(id, it, arg("to")?.takeIf { t -> t.isNotBlank() }) } ?: missing("path")
                "generate_image" -> arg("prompt")?.takeIf { it.isNotBlank() }?.let {
                    ToolCall.GenerateImage(id, it, arg("filename")?.takeIf { f -> f.isNotBlank() }, arg("aspect_ratio")?.takeIf { a -> a.isNotBlank() })
                } ?: missing("prompt")
                "attach_files", "attach_file" -> {
                    // files: [{path, name?}] or plain path strings; a single `path` (+ `name`) works too.
                    val specs = when (val f = args["files"] ?: args["paths"]) {
                        is JsonArray -> f.mapNotNull { el ->
                            when (el) {
                                is JsonObject -> (el["path"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { p ->
                                    ToolCall.AttachSpec(p, (el["name"] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.takeIf { it.isNotBlank() })
                                }
                                is JsonPrimitive -> el.content.takeIf { it.isNotBlank() }?.let { ToolCall.AttachSpec(it) }
                                else -> null
                            }
                        }
                        is JsonPrimitive -> stringList(f).map { ToolCall.AttachSpec(it) }
                        else -> emptyList()
                    }.ifEmpty { arg("path")?.takeIf { it.isNotBlank() }?.let { listOf(ToolCall.AttachSpec(it, arg("name")?.takeIf { n -> n.isNotBlank() })) }.orEmpty() }
                    if (specs.isEmpty()) missing("files") else ToolCall.AttachFiles(id, specs)
                }
                "check_tools" -> {
                    val tools = stringList(args["tools"]).ifEmpty { stringList(args["tool"]) }
                    if (tools.isEmpty()) missing("tools") else ToolCall.CheckTools(id, tools)
                }
                "view_image" -> arg("path")?.takeIf { it.isNotBlank() }?.let { ToolCall.ViewImage(id, it) } ?: missing("path")
                else -> ToolCall.Invalid(id, name, rawArgs, "Unknown tool \"$name\". Available: $TOOL_NAMES, conversation_memory (only when enabled).")
            }
        }

        /** A JSON array of strings, or one space/comma separated string, as a list. */
        private fun stringList(el: JsonElement?): List<String> = when (el) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> if (el.isString) el.content.split(Regex("[\\s,]+")) else emptyList()
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }

        private const val MAX_HISTORY_TURNS = 24
        private const val MAX_TOOL_OUTPUT = 16_000

        /** The agent instructions for cloud models (OpenRouter, Ollama Cloud, custom providers). */
        internal val SYSTEM_PROMPT = """
            You are a capable, autonomous agent running on the user's Android phone. You get things done by calling
            tools; every tool call is really executed on the device or the web, and you see the real output.
            Work in /workspace, this conversation's private folder (the user sees it in the app's file manager).

            # How to work
            - Understand the goal, then act. For anything beyond a quick answer, plan the steps briefly in your head
              and carry them out with tools until the task is actually done; do not stop at instructions for the user
              when you can do it yourself.
            - Verify instead of guessing: search and fetch before stating facts that may have changed, check that
              files exist, read command output. Never invent facts, sources, files, URLs or command output.
            - If a step fails, read the error, fix the cause and retry a different way (another flag, another
              source, install what is missing). Give up only after a real attempt, and then say exactly what failed.
            - Ask the user only when the request is truly ambiguous or an action is risky and not clearly wanted.
            - Prefer several independent tool calls in one step when they do not depend on each other.

            # Tools
            Web
            - web_search: real web search (titles, URLs, snippets). Use it to find pages, videos, docs, current facts.
            - web_fetch: read a page's text. Fetch the pages you rely on; only cite pages you actually fetched.
            - download_file: save any URL (PDF, image, video file, archive, dataset) into the workspace.
            - http_request: call a web API (GET/POST/PUT/PATCH/DELETE, headers, JSON body). Non-GET asks the user.
            Commands and code
            - shell: run a command in /workspace. Built-ins (ls, cat, grep, find, mv, cp, rm, ...) run directly; the
              rest runs in the real Termux environment (bash, apt, pkg, pip, python, ffmpeg, yt-dlp, git, ...).
              Always pass -y to apt/pkg (no interactive input). Commands time out after 60 s (installs 300 s), so
              keep long jobs short or split them (e.g. download one video at a time).
            - check_tools: check that programs (yt-dlp, ffmpeg, git, node, ...) are installed before a command that
              needs them. A shell command whose program is missing is not run; you get the install command instead.
              Then install it yourself (`pip install -U yt-dlp`, `pkg install -y ffmpeg`) when the task clearly needs
              it, or offer to. If python/pip itself is missing: `pkg install -y python`.
            - python: run Python 3 code (Termux's Python) in /workspace; list pip packages in `packages` to install
              them first. Real imports, file I/O and subprocess. Use it for data work, conversions and calculations.
            Files (paths are relative to /workspace)
            - list_files / search_files / file_info: browse folders, grep text, get size/type/dimensions/duration.
            - read_file / write_file / edit_file: read text, write whole files, replace an exact snippet (prefer
              edit_file for small changes). Write documents as Markdown unless another format is asked for.
            - move_file / copy_file / delete_file: rename, move, copy, delete (deleting asks the user).
            - zip_files / unzip_file: pack files into a .zip or extract one.
            - view_image: look at a workspace image (or frames of a video) when the task needs to see it. Imports the
              user asked you to look at are already shown with their message; never look when told not to.
            Results
            - attach_files: attach the files you produced to your answer (see Artifacts).
            - generate_image: create a picture from a description; it is saved and attached automatically.
            - device_info: current date, time, time zone, locale and Android version.

            # Artifacts
            - When you produce a file the user wants (a downloaded video or song, a converted or generated image, a
              PDF, a report, an archive), finish by calling attach_files with it, so it appears under your answer as a
              tappable chip that opens the built-in viewer/player. Attach several files at once if there are several.
            - Do not paste file contents, base64 or a code block instead of attaching, and do not tell the user to dig
              through the workspace.
            - Files often get ugly auto-generated names (`video_7312345678901.mp4`, `out.mp4`, `tmpa8f3.png`,
              `%(title)s` leftovers). Give every attached file a short, human-readable `name`, e.g.
              "Cat jumps into the pool.mp4"; the file is renamed in place. Keep the right extension.

            # Example: "download this TikTok video" (or YouTube, Instagram, X, ...)
            1. If you only have a description, web_search for the video and take its page URL from the results.
            2. check_tools ["yt-dlp", "ffmpeg"]; install what is missing (`pip install -U yt-dlp`,
               `pkg install -y ffmpeg`).
            3. shell: `yt-dlp -o "downloads/%(title).80s.%(ext)s" "<url>"` (add `-f "bv*+ba/b"` or
               `--merge-output-format mp4` when needed). Read the output; on an error, update yt-dlp
               (`pip install -U yt-dlp`) and retry once.
            4. list_files downloads/ to get the exact file name.
            5. attach_files with that path and a clean name.
            6. Answer in one or two sentences ("Here's the video: …"). No code blocks, no instructions.

            # Mentions and imports
            - The user can mention workspace files as @path or @"path with spaces"; that is the file they mean.
            - Imported files arrive as @imports/<id>/<name>; work with them by path.

            # Rules
            - If a command says Termux is not available or permission is denied, tell the user what to set up
              (Settings > General > Sandbox) instead of pretending it worked.
            - Respect the permission prompts: if the user denies an action, do not retry it; find another way or stop.
            - Keep the final answer short, direct and in the user's language. Do not narrate the tools you used;
              the app shows every step. Use Markdown only when it helps (lists, tables, short code the user asked for).
        """.trimIndent()

        /**
         * Shorter instructions for on-device models, whose context windows are small (often 4K tokens):
         * the same rules and the artifacts habit, without the long tool guide.
         */
        internal val COMPACT_SYSTEM_PROMPT = """
            You are an agent on an Android phone with a private workspace at /workspace. Tool calls are really executed.
            - web_search / web_fetch: find and read pages; only cite pages you fetched.
            - shell: run commands (Termux: bash, pkg, pip, python). Pass -y to pkg/apt. Programs that are missing are
              reported with their install command (check_tools checks first); install them when the task needs it.
            - python: run Python code; `packages` are pip-installed first.
            - read_file / write_file / edit_file / list_files: work with workspace files (paths relative to /workspace).
            - attach_files: attach files you produced to your answer with a short human-readable `name` (rename ugly
              auto-generated names). Do not paste file contents instead.
            - device_info: date, time and device facts.
            Never invent facts, files or command output. If something fails, say so plainly. Keep answers short.
        """.trimIndent()

        private fun fn(name: String, description: String, vararg params: Pair<String, String>): JsonElement = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        params.forEach { (p, d) -> putJsonObject(p) { put("type", "string"); put("description", d) } }
                    }
                    putJsonArray("required") { params.forEach { add(it.first) } }
                }
            }
        }

        private val PYTHON_TOOL: JsonElement = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "python")
                put(
                    "description",
                    "Run Python 3 code in the sandbox (Termux's Python) with /workspace as working directory. " +
                        "Packages listed in `packages` are pip-installed first. Returns stdout, stderr and the exit code.",
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("code") { put("type", "string"); put("description", "Full Python source to run") }
                        putJsonObject("packages") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("description", "pip packages to install before running, e.g. [\"yt-dlp\", \"requests\"]")
                        }
                        putJsonObject("filename") {
                            put("type", "string")
                            put("description", "Optional script name relative to /workspace, e.g. download.py")
                        }
                    }
                    putJsonArray("required") { add("code") }
                }
            }
        }

        /** One-line list of the agent's tools, shown in Settings > About. */
        const val TOOL_NAMES = "web_search, web_fetch, shell, python, read_file, write_file, edit_file, list_files, " +
            "search_files, move_file, copy_file, delete_file, file_info, zip_files, unzip_file, download_file, http_request, " +
            "device_info, check_tools, attach_files, generate_image, view_image"

        const val TOOL_SUMMARY = "web_search, web_fetch, shell (Termux: bash, apt, pkg, pip), python, read_file, write_file, " +
            "edit_file, list_files, search_files, move_file, copy_file, delete_file, file_info, zip_files, unzip_file, download_file, " +
            "http_request, device_info, check_tools, attach_files, generate_image, view_image, " +
            "and conversation_memory when memory of past chats is on."

        const val MEMORY_TOOL_NAME = "conversation_memory"

        /** Read-only memory of earlier conversations; only sent when the user enabled it. */
        val MEMORY_TOOL: JsonElement = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", MEMORY_TOOL_NAME)
                put(
                    "description",
                    "Read the user's earlier conversations with you (read-only). Start with list_conversations " +
                        "(short lines, optional query to search), then summarize_conversation for one id, and only " +
                        "use read_conversation (paged by message offset) when you need exact details.",
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            putJsonArray("enum") { com.sebialex.agent.domain.model.MEMORY_ACTIONS.forEach { add(it) } }
                            put("description", "What to do")
                        }
                        putJsonObject("conversation_id") { put("type", "string"); put("description", "Conversation id from list_conversations") }
                        putJsonObject("offset") { put("type", "integer"); put("description", "First message index for read_conversation, default 0") }
                        putJsonObject("query") { put("type", "string"); put("description", "Optional text to search for with list_conversations") }
                    }
                    putJsonArray("required") { add("action") }
                }
            }
        }

        /** Extra system prompt lines when memory is on. */
        internal const val MEMORY_PROMPT =
            "Memory of earlier conversations is on: use the conversation_memory tool when the user refers to a " +
                "previous chat or earlier work. Start from list_conversations or a summary; read full messages only " +
                "when needed. It is read-only.\n"

        /**
         * The tools to send, plus conversation memory when it is on. [compact] is the smaller set for
         * on-device models with a short context window (the core tools plus list/edit/device info).
         */
        fun toolsFor(memory: Boolean, compact: Boolean = false): JsonArray {
            val base = if (compact) TOOLS.filter { t -> toolNameOf(t) in COMPACT_TOOLS } else TOOLS
            return JsonArray(if (memory) base + MEMORY_TOOL else base)
        }

        private fun toolNameOf(t: JsonElement): String? =
            (((t as? JsonObject)?.get("function") as? JsonObject)?.get("name") as? JsonPrimitive)?.content

        private val COMPACT_TOOLS = setOf("web_search", "web_fetch", "shell", "python", "read_file", "write_file", "edit_file", "list_files", "device_info", "check_tools", "attach_files")

        /** A function schema with typed parameters: (name, type, description, required). */
        private fun typedFn(name: String, description: String, vararg params: Param): JsonElement = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        params.forEach { p ->
                            putJsonObject(p.name) {
                                put("type", p.type)
                                put("description", p.description)
                                if (p.type == "object") putJsonObject("additionalProperties") { put("type", "string") }
                                p.enum?.let { values -> putJsonArray("enum") { values.forEach { add(it) } } }
                            }
                        }
                    }
                    putJsonArray("required") { params.filter { it.required }.forEach { add(it.name) } }
                }
            }
        }

        /** attach_files: files: [{path, name?}]. */
        private val ATTACH_TOOL: JsonElement = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", "attach_files")
                put(
                    "description",
                    "Attach workspace files you produced (video, image, audio, PDF, document, archive) to your answer. " +
                        "The user sees each one as a compact chip that opens the built-in viewer/player. Give a short, " +
                        "human-readable `name` when the file has an ugly auto-generated one; the file is renamed in place.",
                )
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("files") {
                            put("type", "array")
                            put("description", "Files to attach")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("path") { put("type", "string"); put("description", "Path relative to /workspace") }
                                    putJsonObject("name") { put("type", "string"); put("description", "Optional friendly file name, e.g. \"Cat jumping.mp4\"") }
                                }
                                putJsonArray("required") { add("path") }
                            }
                        }
                    }
                    putJsonArray("required") { add("files") }
                }
            }
        }

        /** A function whose one required parameter is an array of strings. */
        private fun arrayFn(name: String, description: String, param: String, paramDescription: String): JsonElement = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject(param) {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("description", paramDescription)
                        }
                    }
                    putJsonArray("required") { add(param) }
                }
            }
        }

        private class Param(val name: String, val type: String, val description: String, val required: Boolean = true, val enum: List<String>? = null)

        val TOOLS: JsonArray = JsonArray(
            listOf(
                fn("web_search", "Search the web. Returns result titles, URLs and snippets.", "query" to "Search query"),
                fn("web_fetch", "Fetch a URL and return its readable text content.", "url" to "Absolute http(s) URL"),
                fn("shell", "Run a command in the sandboxed workspace. Built-ins run directly; anything else runs in Termux (bash, apt, pkg, pip, python). Working directory is /workspace.", "command" to "Command line"),
                PYTHON_TOOL,
                fn("read_file", "Read a text file from the workspace.", "path" to "Path relative to /workspace"),
                fn("write_file", "Create or overwrite a workspace file with the full content.", "path" to "Path relative to /workspace", "content" to "Full file content"),
                typedFn(
                    "edit_file", "Replace an exact piece of text in a workspace file. old_text must match exactly once unless replace_all is true.",
                    Param("path", "string", "Path relative to /workspace"),
                    Param("old_text", "string", "Exact text to find (include enough context to be unique)"),
                    Param("new_text", "string", "Replacement text"),
                    Param("replace_all", "boolean", "Replace every occurrence", required = false),
                ),
                typedFn(
                    "list_files", "List files and folders in the workspace with sizes.",
                    Param("path", "string", "Folder relative to /workspace, default /", required = false),
                    Param("recursive", "boolean", "Include subfolders", required = false),
                ),
                typedFn(
                    "search_files", "Search text inside workspace files. Returns matching lines as path:line: text.",
                    Param("query", "string", "Text to find (case-insensitive), or a regular expression when regex is true"),
                    Param("path", "string", "Folder to search, default /", required = false),
                    Param("regex", "boolean", "Treat query as a regular expression", required = false),
                ),
                typedFn(
                    "move_file", "Move or rename a workspace file or folder.",
                    Param("from", "string", "Current path relative to /workspace"),
                    Param("to", "string", "New path relative to /workspace"),
                ),
                typedFn("delete_file", "Delete a workspace file or folder (the user is asked first).", Param("path", "string", "Path relative to /workspace")),
                typedFn(
                    "download_file", "Download a URL (any file type) into the workspace.",
                    Param("url", "string", "Absolute http(s) URL"),
                    Param("path", "string", "Target path relative to /workspace; default downloads/<file name>", required = false),
                ),
                typedFn(
                    "http_request", "Send an HTTP request to an API and return status, headers and body text.",
                    Param("method", "string", "HTTP method", enum = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
                    Param("url", "string", "Absolute http(s) URL"),
                    Param("headers", "object", "Request headers", required = false),
                    Param("body", "string", "Request body, e.g. JSON text", required = false),
                ),
                typedFn("device_info", "Current date, time, time zone, locale and Android version of this phone."),
                ATTACH_TOOL,
                typedFn(
                    "copy_file", "Copy a workspace file or folder.",
                    Param("from", "string", "Source path relative to /workspace"),
                    Param("to", "string", "Destination path relative to /workspace"),
                ),
                typedFn(
                    "file_info", "Facts about a workspace file or folder: size, type, modified time, image dimensions, " +
                        "video/audio duration and resolution, line count for text.",
                    Param("path", "string", "Path relative to /workspace"),
                ),
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "zip_files")
                        put("description", "Pack workspace files and folders into a .zip archive.")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("paths") {
                                    put("type", "array"); putJsonObject("items") { put("type", "string") }
                                    put("description", "Files or folders to include, relative to /workspace")
                                }
                                putJsonObject("output") { put("type", "string"); put("description", "Archive path, e.g. exports/photos.zip") }
                            }
                            putJsonArray("required") { add("paths"); add("output") }
                        }
                    }
                },
                typedFn(
                    "unzip_file", "Extract a .zip archive inside the workspace.",
                    Param("path", "string", "Archive path relative to /workspace"),
                    Param("to", "string", "Target folder; default: a folder named after the archive", required = false),
                ),
                typedFn(
                    "generate_image", "Create a picture from a text description with an image model. The image is saved in " +
                        "the workspace and attached to your answer automatically.",
                    Param("prompt", "string", "Detailed description of the picture: subject, style, composition, lighting"),
                    Param("filename", "string", "Optional human-readable path, e.g. images/Red panda astronaut.png", required = false),
                    Param("aspect_ratio", "string", "Optional, e.g. 1:1, 16:9, 9:16, 4:3", required = false),
                ),
                arrayFn(
                    "check_tools",
                    "Check whether command-line programs (yt-dlp, ffmpeg, git, node, ...) are installed in the sandbox. " +
                        "Returns the path of each installed one and the install command for each missing one. " +
                        "Call it before a command that depends on such a program.",
                    "tools", "Program names, e.g. [\"yt-dlp\", \"ffmpeg\"]",
                ),
                typedFn(
                    "view_image", "Look at a workspace image (png, jpg, webp, gif) or a few frames of a video. The picture is shown to you after the tool result.",
                    Param("path", "string", "Path relative to /workspace, e.g. imports/photo.jpg"),
                ),
            ),
        )
    }
}
