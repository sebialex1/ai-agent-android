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
) : AgentBrain {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun next(ctx: BrainContext): BrainDecision = withContext(Dispatchers.IO) {
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
            put("tools", TOOLS)
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            reasoning.forEach { (k, v) -> put(k, v) }
        }.toString()
        val root = json.parseToJsonElement(post(body)).jsonObject
        (root["error"] as? JsonObject)?.let { err ->
            throw IOException((err["message"] as? JsonPrimitive)?.content ?: "OpenRouter error")
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw IOException("OpenRouter returned no choices")
        val message = choice["message"] as? JsonObject ?: throw IOException("OpenRouter returned no message")

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
        val args = runCatching { json.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
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
            else -> ToolCall.Invalid(id, name, rawArgs, "Unknown tool \"$name\". Available: web_search, web_fetch, shell, read_file, write_file.")
        }
    }

    /** Content may be a plain string or an array of content parts. */
    private fun contentText(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> if (el.isString) el.content else ""
        is JsonArray -> el.joinToString("") { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
        }
        else -> ""
    }

    private fun messages(ctx: BrainContext): JsonArray = buildJsonArray {
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
        return SYSTEM_PROMPT + "\nToday is $date.\n" +
            if (files.isEmpty()) "The workspace is empty." else "Workspace files:\n" + files.joinToString("\n")
    }

    private fun toolName(c: ToolCall) = when (c) {
        is ToolCall.WebSearch -> "web_search"
        is ToolCall.WebFetch -> "web_fetch"
        is ToolCall.Shell -> "shell"
        is ToolCall.ReadFile -> "read_file"
        is ToolCall.WriteFile -> "write_file"
        is ToolCall.Invalid -> c.name.ifBlank { "unknown" }
    }

    private fun arguments(c: ToolCall): String = when (c) {
        is ToolCall.WebSearch -> buildJsonObject { put("query", c.query) }.toString()
        is ToolCall.WebFetch -> buildJsonObject { put("url", c.url) }.toString()
        is ToolCall.Shell -> buildJsonObject { put("command", c.command) }.toString()
        is ToolCall.ReadFile -> buildJsonObject { put("path", c.path) }.toString()
        is ToolCall.WriteFile -> buildJsonObject { put("path", c.path); put("content", c.content) }.toString()
        is ToolCall.Invalid -> c.arguments
    }

    private fun post(body: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("HTTP-Referer", "https://github.com/sebialex1/ai-agent-android")
            conn.setRequestProperty("X-Title", "Agent for Android")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = runCatching {
                    ((json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content
                }.getOrNull()
                throw IOException("HTTP $code" + if (!detail.isNullOrBlank()) ": $detail" else "")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val MAX_HISTORY_TURNS = 24
        private const val MAX_TOOL_OUTPUT = 16_000

        private val SYSTEM_PROMPT = """
            You are an agent running on an Android device with a private, sandboxed workspace at /workspace.
            You act through tools and every tool call is really executed:
            - web_search: real web search. Returns titles, URLs and snippets.
            - web_fetch: downloads a page and returns its text. Fetch pages before relying on them.
            - shell: runs a command in the workspace (coreutils-style built-ins, otherwise the device's /system/bin/sh).
            - read_file / write_file: read and write workspace files. Write documents as Markdown.
            Rules:
            - Never invent facts, sources, files or command output. If a tool fails, say so plainly.
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

        val TOOLS: JsonArray = JsonArray(
            listOf(
                fn("web_search", "Search the web. Returns result titles, URLs and snippets.", "query" to "Search query"),
                fn("web_fetch", "Fetch a URL and return its readable text content.", "url" to "Absolute http(s) URL"),
                fn("shell", "Run a command in the sandboxed workspace shell. Working directory is /workspace.", "command" to "Command line"),
                fn("read_file", "Read a text file from the workspace.", "path" to "Path relative to /workspace"),
                fn("write_file", "Create or overwrite a workspace file with the full content.", "path" to "Path relative to /workspace", "content" to "Full file content"),
            ),
        )
    }
}
