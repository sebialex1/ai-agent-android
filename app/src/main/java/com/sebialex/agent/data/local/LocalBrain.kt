package com.sebialex.agent.data.local

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.domain.agent.AgentBrain
import com.sebialex.agent.domain.agent.BrainContext
import com.sebialex.agent.domain.agent.BrainDecision
import com.sebialex.agent.domain.agent.Observation
import com.sebialex.agent.domain.agent.Thought
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.ai.edge.litertlm.ToolCall as LmToolCall

/**
 * Agent brain that runs fully on this phone with LiteRT-LM. It uses the same tools, system
 * prompt and tool-call parsing as the OpenRouter brain, so steps, approvals and the audit log
 * behave identically; only the model runs locally.
 *
 * Each request builds a fresh LiteRT-LM conversation from the real exchange: earlier turns, the
 * task, then every tool batch of this turn as an assistant message with its tool calls followed
 * by one tool message with the actual outputs. Tool calls are returned to the app
 * (`automaticToolCalling = false`) and never executed by LiteRT-LM itself, so every action still
 * goes through the permission policy.
 */
class LocalBrain(
    private val engine: LocalEngine,
    private val store: ModelStore,
    private val modelId: String,
    /** Whether the user turned thinking on for this model (Settings or the + sheet). */
    private val thinking: () -> Boolean,
) : AgentBrain {

    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
        val model = store.model(modelId) ?: throw IOException("Unknown on-device model \"$modelId\".")
        return engine.withEngine(model) { eng, loaded ->
            withContext(Dispatchers.IO) { answer(eng, loaded, ctx, onReasoning) }
        }
    }

    private suspend fun answer(eng: Engine, loaded: LoadedModel, ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
        val budget = Budget(loaded.contextTokens)
        val (initial, last) = transcript(ctx, loaded, budget)
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt(ctx, loaded)),
            initialMessages = initial,
            tools = if (loaded.toolCalling) toolProviders(ctx.memoryTool) else emptyList(),
            // The app executes tools itself, after the permission policy.
            automaticToolCalling = false,
        )
        val conversation = eng.createConversation(config)
        val reply = try {
            stream(conversation, last, mapOf(ENABLE_THINKING to (loaded.thinking && thinking())), onReasoning)
        } finally {
            runCatching { conversation.close() }
        }

        val thought = reply.thought.trim().takeIf { it.isNotEmpty() }?.let { Thought(it) }
        if (reply.calls.isNotEmpty()) {
            val calls = reply.calls.mapIndexed { i, c ->
                OpenRouterBrain.parseToolCall("local_${ctx.observations.size + i + 1}", c.name, toJson(c.arguments).toString())
            }
            return BrainDecision.Act(calls, thought)
        }
        val text = reply.text.trim()
        if (text.isEmpty()) throw IOException("the on-device model returned an empty answer")
        return BrainDecision.Answer(text, thought)
    }

    private class Reply(val text: String, val thought: String, val calls: List<LmToolCall>)

    /**
     * Sends [message] and collects the streamed answer. Thought-channel deltas go to
     * [onReasoning] live. When the caller is cancelled (Stop), the native decode is cancelled and
     * awaited before the conversation is closed, so no native work outlives it.
     */
    private suspend fun stream(
        conversation: Conversation,
        message: Message,
        extraContext: Map<String, Any>,
        onReasoning: (String) -> Unit,
    ): Reply {
        val lock = Any()
        val text = StringBuilder()
        val thought = StringBuilder()
        val calls = ArrayList<LmToolCall>()
        val done = CompletableDeferred<Unit>()
        conversation.sendMessageAsync(
            message,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    synchronized(lock) {
                        message.channels[THOUGHT_CHANNEL]?.takeIf { it.isNotEmpty() }?.let {
                            thought.append(it)
                            runCatching { onReasoning(it) }
                        }
                        message.contents.contents.forEach { if (it is Content.Text) text.append(it.text) }
                        calls.addAll(message.toolCalls)
                    }
                }

                override fun onDone() {
                    done.complete(Unit)
                }

                override fun onError(throwable: Throwable) {
                    // A native cancel is reported as a CancellationException; never let that be
                    // mistaken for this coroutine being cancelled.
                    done.completeExceptionally(
                        if (throwable is java.util.concurrent.CancellationException) IOException("generation was stopped") else throwable,
                    )
                }
            },
            extraContext,
        )
        try {
            done.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!done.isCompleted) {
                runCatching { conversation.cancelProcess() }
                withContext(NonCancellable) { withTimeoutOrNull(CANCEL_WAIT_MS) { runCatching { done.await() } } }
            }
            throw e
        }
        return synchronized(lock) { Reply(text.toString(), thought.toString(), calls.toList()) }
    }

    /** Rough character budget derived from the context window (about 4 characters per token). */
    private class Budget(contextTokens: Int) {
        /** Characters available for system prompt + transcript; the rest is left for the answer. */
        val promptChars = (contextTokens * 4 * 3 / 4).coerceAtLeast(2_000)
        /** Longest tool output replayed in full. */
        val toolChars = contextTokens.coerceIn(1_200, 16_000)
        /** Older tool outputs are cut harder when the transcript gets long. */
        val oldToolChars = 600
        val historyTurnChars = (contextTokens / 2).coerceIn(600, 6_000)
    }

    /**
     * The conversation to replay ([first]) and the message to send now ([second]). Consecutive
     * messages of the same role are merged, because Gemma-style chat templates require user and
     * model turns to alternate.
     */
    private fun transcript(ctx: BrainContext, loaded: LoadedModel, budget: Budget): Pair<List<Message>, Message> {
        val turns = ArrayList<Pair<Role, String>>()
        ctx.history.filter { it.complete || it.role == Role.USER }.takeLast(MAX_HISTORY_TURNS).forEach { t ->
            if (t.text.isBlank()) return@forEach
            turns += t.role to t.text.take(budget.historyTurnChars)
        }
        // On-device models here take text only: say so instead of claiming a picture is shown.
        turns += Role.USER to if (ctx.images.isEmpty()) ctx.task else ctx.task + "\n\n" + LOCAL_NO_IMAGES_NOTE
        val merged = ArrayList<Pair<Role, String>>()
        for ((role, text) in turns) {
            val prev = merged.lastOrNull()
            if (prev != null && prev.first == role) merged[merged.lastIndex] = role to (prev.second + "\n\n" + text)
            else merged += role to text
        }
        // A transcript must open with the user.
        while (merged.firstOrNull()?.first == Role.ASSISTANT) merged.removeAt(0)

        val batches = if (loaded.toolCalling) ctx.observations.groupBy { it.batch }.toSortedMap().values.toList() else emptyList()
        val toolChars = batches.sumOf { g -> g.sumOf { it.result.output.length.coerceAtMost(budget.toolChars) } }
        val fixed = systemPromptLength(ctx) + merged.last().second.length + toolChars
        // Drop the oldest history while the prompt does not fit (the task itself always stays).
        var historyChars = merged.dropLast(1).sumOf { it.second.length }
        while (merged.size > 1 && fixed + historyChars > budget.promptChars) {
            historyChars -= merged.removeAt(0).second.length
            while (merged.size > 1 && merged.first().first == Role.ASSISTANT) historyChars -= merged.removeAt(0).second.length
        }
        val tight = fixed > budget.promptChars

        val messages = ArrayList<Message>()
        merged.forEach { (role, text) -> messages += if (role == Role.USER) Message.user(text) else Message.model(text) }
        batches.forEachIndexed { i, group ->
            val latest = i == batches.lastIndex
            val limit = if (tight && !latest) budget.oldToolChars else budget.toolChars
            messages += Message.model(toolCalls = group.map { o -> LmToolCall(OpenRouterBrain.toolName(o.call), argumentsOf(o.call)) })
            messages += Message.tool(Contents.of(group.map { o -> Content.ToolResponse(OpenRouterBrain.toolName(o.call), toolOutput(o, limit)) }))
        }
        val last = messages.removeAt(messages.lastIndex)
        return messages to last
    }

    private fun toolOutput(o: Observation, limit: Int): String {
        val body = o.result.output.ifEmpty { if (o.result.ok) "(no output)" else "(failed with no output)" }
        val code = o.result.exitCode?.let { "exit code $it\n" }.orEmpty()
        val cut = if (body.length > limit) body.take(limit) + "\n[... ${body.length - limit} more characters cut]" else body
        return (if (o.result.ok) "" else "ERROR: ") + code + cut
    }

    private fun systemPrompt(ctx: BrainContext, loaded: LoadedModel): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val files = ctx.workspaceFiles.take(MAX_FILES)
        val base = if (loaded.toolCalling) OpenRouterBrain.COMPACT_SYSTEM_PROMPT else NO_TOOLS_PROMPT
        val memory = if (loaded.toolCalling && ctx.memoryTool) "\n" + OpenRouterBrain.MEMORY_PROMPT else ""
        return base + memory + "\nYou run fully on this phone (${loaded.name}, ${loaded.backend}). Today is $date.\n" +
            if (files.isEmpty()) "The workspace is empty." else "Workspace files:\n" + files.joinToString("\n")
    }

    private fun systemPromptLength(ctx: BrainContext) =
        OpenRouterBrain.COMPACT_SYSTEM_PROMPT.length + 200 + ctx.workspaceFiles.take(MAX_FILES).sumOf { it.length + 1 } + TOOL_SCHEMA_CHARS +
            if (ctx.memoryTool) OpenRouterBrain.MEMORY_PROMPT.length + MEMORY_SCHEMA_CHARS else 0

    /** The tool call's arguments as plain Kotlin values for LiteRT-LM's chat template. */
    private fun argumentsOf(call: ToolCall): Map<String, Any?> {
        val raw = OpenRouterBrain.arguments(call)
        val obj = runCatching { STRICT.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return mapOf("raw" to raw)
        return obj.mapValues { (_, v) -> fromJson(v) }
    }

    private fun fromJson(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.content.toBooleanStrictOrNull() ?: e.content.toLongOrNull() ?: e.content.toDoubleOrNull() ?: e.content
        is JsonArray -> e.map { fromJson(it) }
        is JsonObject -> e.mapValues { (_, v) -> fromJson(v) }
    }

    /** LiteRT-LM's parsed arguments (strings, numbers, booleans, lists, maps) back to JSON. */
    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { e -> e.key.toString() to toJson(e.value) })
        is List<*> -> JsonArray(v.map { toJson(it) })
        // Anything else (e.g. a raw JSON element) prints as JSON; keep it as text otherwise.
        else -> v.toString().let { s -> runCatching { STRICT.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) } }
    }

    companion object {
        /** LiteRT-LM's channel for reasoning tokens. */
        private const val THOUGHT_CHANNEL = "thought"
        /** Chat-template switch for models with a thinking mode (Gemma 4). */
        private const val ENABLE_THINKING = "enable_thinking"
        private const val MAX_HISTORY_TURNS = 12
        /** Replaces the "shown below" note: on-device models in this app get no image input. */
        private const val LOCAL_NO_IMAGES_NOTE = "[The picture is not visible to you: this on-device model takes text only. " +
            "Say so briefly if the task needs to see it, and suggest a cloud vision model.]"
        private const val MAX_FILES = 60
        private const val CANCEL_WAIT_MS = 5_000L
        /** Approximate size of the compact tool schemas once rendered into the prompt. */
        private const val TOOL_SCHEMA_CHARS = 4_600
        private const val MEMORY_SCHEMA_CHARS = 700
        private val STRICT = Json

        private val NO_TOOLS_PROMPT = """
            You are a helpful assistant running fully on an Android phone. This model has no tool access here:
            you cannot browse, run commands or read files. Answer from what you know, say so plainly when you
            are unsure, and keep answers short and direct. Do not write tool calls or tool-call JSON
            ({"action": ...}) into your answer: nothing would run them.
        """.trimIndent()

        /**
         * The agent's compact tool set (plus conversation memory when it is on), described to LiteRT-LM with
         * the exact OpenRouter schemas. They are never executed by LiteRT-LM (automatic tool calling
         * is off).
         */
        private fun toolProviders(memory: Boolean): List<ToolProvider> = OpenRouterBrain.toolsFor(memory, compact = true).mapNotNull { el ->
            val fn = (el as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
            tool(SchemaTool(fn.toString()))
        }

        private class SchemaTool(private val schema: String) : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = schema
            override fun execute(paramsJsonString: String): String = """{"error":"tools are executed by the app"}"""
        }
    }
}
