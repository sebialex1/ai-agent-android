package com.sebialex.agent.data.llm

import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.ToolCall
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM brain backed by OpenRouter's OpenAI-compatible chat completions endpoint with tool calling.
 * The model id comes from settings (default "openrouter/auto"), never hard-coded in the loop.
 */
class OpenRouterBrain(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
) : AgentBrain {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun next(ctx: BrainContext): BrainDecision = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("messages", messages(ctx))
            put("tools", TOOLS)
            put("tool_choice", "auto")
            put("temperature", 0.2)
        }.toString()
        val response = post(body)
        val message = json.parseToJsonElement(response).jsonObject["choices"]
            .let { it as? JsonArray }?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            ?: throw IOException("empty response")
        val calls = message["tool_calls"] as? JsonArray
        val first = calls?.firstOrNull() as? JsonObject
        if (first != null) {
            val id = (first["id"] as? JsonPrimitive)?.content ?: "call_${ctx.observations.size + 1}"
            val fn = first["function"] as? JsonObject ?: throw IOException("malformed tool call")
            val name = (fn["name"] as? JsonPrimitive)?.content ?: ""
            val args = runCatching { json.parseToJsonElement((fn["arguments"] as? JsonPrimitive)?.content ?: "{}").jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
            fun arg(k: String) = (args[k] as? JsonPrimitive)?.content ?: ""
            val call = when (name) {
                "web_search" -> ToolCall.WebSearch(id, arg("query"))
                "web_fetch" -> ToolCall.WebFetch(id, arg("url"))
                "shell" -> ToolCall.Shell(id, arg("command"))
                "read_file" -> ToolCall.ReadFile(id, arg("path"))
                "write_file" -> ToolCall.WriteFile(id, arg("path"), arg("content"))
                else -> null
            }
            if (call != null) return@withContext BrainDecision.Act(call)
        }
        BrainDecision.Answer((message["content"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "Done." })
    }

    private fun messages(ctx: BrainContext): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", SYSTEM_PROMPT + "\nWorkspace files:\n" + ctx.workspaceFiles.take(200).joinToString("\n"))
        })
        ctx.history.takeLast(12).forEach { t ->
            if (t.text.isNotBlank()) add(buildJsonObject {
                put("role", if (t.role == Role.USER) "user" else "assistant")
                put("content", t.text)
            })
        }
        add(buildJsonObject { put("role", "user"); put("content", ctx.task) })
        ctx.observations.forEach { o ->
            add(buildJsonObject {
                put("role", "assistant")
                put("content", "")
                putJsonArray("tool_calls") {
                    add(buildJsonObject {
                        put("id", o.call.callId)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", toolName(o.call))
                            put("arguments", arguments(o.call).toString())
                        }
                    })
                }
            })
            add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", o.call.callId)
                put("content", (if (o.result.ok) "" else "ERROR: ") + o.result.output.take(12_000))
            })
        }
    }

    private fun toolName(c: ToolCall) = when (c) {
        is ToolCall.WebSearch -> "web_search"
        is ToolCall.WebFetch -> "web_fetch"
        is ToolCall.Shell -> "shell"
        is ToolCall.ReadFile -> "read_file"
        is ToolCall.WriteFile -> "write_file"
    }

    private fun arguments(c: ToolCall): JsonObject = buildJsonObject {
        when (c) {
            is ToolCall.WebSearch -> put("query", c.query)
            is ToolCall.WebFetch -> put("url", c.url)
            is ToolCall.Shell -> put("command", c.command)
            is ToolCall.ReadFile -> put("path", c.path)
            is ToolCall.WriteFile -> { put("path", c.path); put("content", c.content) }
        }
    }

    private fun post(body: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("HTTP-Referer", "https://github.com/sebialex1/ai-agent-android")
            conn.setRequestProperty("X-Title", "Agent for Android")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are a local agent on an Android device with a sandboxed workspace mounted at /workspace.
            Use tools to act: web_search and web_fetch for the web, shell for coreutils-style commands,
            read_file and write_file for workspace files. Prefer Markdown for documents you create.
            Keep final answers short and direct. Do not describe the tools you used.
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
                fn("web_search", "Search the web via TinyFish.", "query" to "Search query"),
                fn("web_fetch", "Fetch a URL and return its content as Markdown.", "url" to "Absolute http(s) URL"),
                fn("shell", "Run a command in the sandboxed workspace shell.", "command" to "Command line"),
                fn("read_file", "Read a workspace file.", "path" to "Path relative to /workspace"),
                fn("write_file", "Create or overwrite a workspace file.", "path" to "Path relative to /workspace", "content" to "Full file content"),
            ),
        )
    }
}
