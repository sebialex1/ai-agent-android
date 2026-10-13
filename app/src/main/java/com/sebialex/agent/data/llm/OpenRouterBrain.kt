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
) : AgentBrain {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision = withContext(Dispatchers.IO) {
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
        add(buildJsonObject { put("role", "user"); put("content", ctx.task) })
        ctx.observations.groupBy { it.batch }.toSortedMap().forEach { (batch, group) -> addBatch(group, ctx.thoughts[batch]) }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addBatch(group: List<Observation>, thought: Thought?) {
        add(buildJsonObject {
            put("role", "assistant")
            put("content", "")
            // Replay the model's own reasoning for this step so it keeps its chain of thought.
            if (thought != null && thought.text.isNotBlank()) put("reasoning", thought.text)
            thought?.details?.let { raw ->
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
    }

    /** Short record of what an earlier answer actually did, so follow-ups have real context. */
    private fun actionsNote(t: com.sebialex.agent.domain.model.Turn): String {
        val done = t.steps.filter { it.status == StepStatus.DONE && it.tool != "permission" && it.tool != "reasoning" }
        if (done.isEmpty() && t.attachments.isEmpty()) return ""
        val lines = done.takeLast(12).map { "- ${it.tool}: ${it.payload.take(160)}" } +
            t.attachments.map { "- file: ${it.path}" }
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
            is ToolCall.Invalid -> c.arguments
        }

        /**
         * Turns one tool call (name plus raw JSON arguments) into a [ToolCall], or a
         * [ToolCall.Invalid] that explains what was wrong. Shared by every brain.
         */
        fun parseToolCall(id: String, name: String, rawArgs: String): ToolCall {
            val args = runCatching { ARGS_JSON.parseToJsonElement(rawArgs.ifBlank { "{}" }).jsonObject }.getOrNull()
                ?: return ToolCall.Invalid(id, name, rawArgs, "Arguments were not valid JSON: $rawArgs")
            fun arg(k: String) = (args[k] as? JsonPrimitive)?.content
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
                else -> ToolCall.Invalid(id, name, rawArgs, "Unknown tool \"$name\". Available: web_search, web_fetch, shell, python, read_file, write_file, conversation_memory (only when enabled).")
            }
        }

        private const val MAX_HISTORY_TURNS = 24
        private const val MAX_TOOL_OUTPUT = 16_000

        /** The agent instructions, shared with the on-device brain. */
        internal val SYSTEM_PROMPT = """
            You are an agent running on an Android device with a private, sandboxed workspace at /workspace.
            You act through tools and every tool call is really executed:
            - web_search: real web search. Returns titles, URLs and snippets.
            - web_fetch: downloads a page and returns its text. Fetch pages before relying on them.
            - shell: runs a command in the workspace. Built-ins (ls, cat, grep, ...) run directly; everything else runs
              in the real Termux environment (bash, apt, apt-get, dpkg, pkg, pip, python) inside this workspace.
              Install system tools with `pkg install -y <pkg>` (or `apt install -y <pkg>`) and Python packages with
              `pip install <pkg>`, e.g. `pip install yt-dlp` then `yt-dlp -o "%(title)s.%(ext)s" <url>`.
              Always pass -y to apt/pkg; there is no interactive input. Commands time out after 60 s (installs 300 s).
              If python or pip is missing, install it with `pkg install -y python` first. Do not claim that packages
              cannot be installed unless a real command failed; quote the real error if it did.
            - python: runs Python code with Termux's Python in the workspace; list pip packages in `packages` to install
              them first. Real imports, file I/O and subprocess. Files it writes land in the workspace.
            - read_file / write_file: read and write workspace files. Write documents as Markdown.
            Rules:
            - Never invent facts, sources, files or command output. If a tool fails, say so plainly.
            - If a command reports that Termux is not available or permission is denied, tell the user what to set up
              (Settings > Sandbox) instead of pretending it worked.
            - Only cite websites you actually fetched in this conversation.
            - Keep the final answer short and direct. Do not narrate the tools you used; the app shows them.
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

        /** The tools to send: the six agent tools, plus conversation memory when it is on. */
        fun toolsFor(memory: Boolean): JsonArray = if (memory) JsonArray(TOOLS + MEMORY_TOOL) else TOOLS

        val TOOLS: JsonArray = JsonArray(
            listOf(
                fn("web_search", "Search the web. Returns result titles, URLs and snippets.", "query" to "Search query"),
                fn("web_fetch", "Fetch a URL and return its readable text content.", "url" to "Absolute http(s) URL"),
                fn("shell", "Run a command in the sandboxed workspace. Built-ins run directly; anything else runs in Termux (bash, apt, pkg, pip, python). Working directory is /workspace.", "command" to "Command line"),
                PYTHON_TOOL,
                fn("read_file", "Read a text file from the workspace.", "path" to "Path relative to /workspace"),
                fn("write_file", "Create or overwrite a workspace file with the full content.", "path" to "Path relative to /workspace", "content" to "Full file content"),
            ),
        )
    }
}
