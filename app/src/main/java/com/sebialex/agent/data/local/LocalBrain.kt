package com.sebialex.agent.data.local

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
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
import com.sebialex.agent.domain.model.Turn
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
 * The router builds a fresh brain for every request, so this brain holds no state: the engine
 * owns the live conversation, and reuse is decided per step. A step continues the existing
 * conversation only when it is the same run on the same model and settings, with exactly the
 * same history and task, and the observations are exactly what was already sent plus one new
 * batch answering exactly the calls the model just made; then it sends only that new batch as
 * one tool message. Anything else rebuilds from the full transcript, and the conversation is
 * closed on an answer, an error, Stop, a model switch, an unload, or when the live
 * conversation's running usage estimate passes about two thirds of the context (an estimated
 * prompt budget, not proof of fit).
 *
 * Tool calls are returned to the app (`automaticToolCalling = false`) and never executed by
 * LiteRT-LM itself, so every action still goes through the permission policy.
 */
class LocalBrain(
    private val engine: LocalEngine,
    private val modelId: String,
    private val resolve: () -> LocalModel?,
    /** Whether the user turned thinking on for this model (Settings or the + sheet). */
    private val thinking: () -> Boolean,
) : AgentBrain {

    constructor(engine: LocalEngine, store: ModelStore, modelId: String, thinking: () -> Boolean) :
        this(engine, modelId, { store.model(modelId) }, thinking)

    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
        val model = resolve() ?: throw IOException("Unknown on-device model \"$modelId\".")
        return engine.withEngine(model) { loaded, session ->
            withContext(Dispatchers.IO) { answer(loaded, session, ctx, onReasoning) }
        }
    }

    /**
     * Called once when the turn ends, however it ended. The router builds a new brain per
     * request, so the key is recomputed from the context: only this run's conversation is
     * closed, never another run's.
     */
    override suspend fun endTurn(ctx: BrainContext) {
        if (ctx.runId.isEmpty()) return
        runCatching { engine.closeTurn("${ctx.runId}|") }
    }

    private suspend fun answer(
        loaded: LoadedModel,
        session: LocalEngine.Session,
        ctx: BrainContext,
        onReasoning: (String) -> Unit,
    ): BrainDecision {
        val budget = Budget(loaded.contextTokens)
        val key = turnKey(ctx, loaded)
        // The run's conversations all carry this prefix, whatever the rest of the key is.
        val run = "${ctx.runId}|"
        val live = session.live(key)
        val state = live?.state as? Continuation
        // The new batch and what sending it would add, computed before deciding: a batch that
        // would push the live conversation past the two-thirds bound is never sent
        // incrementally — the rebuild path enforces the hard limit and the exact "too long"
        // error instead. The bound is stricter than the hard limit, so it subsumes it.
        val freshBatch = state?.let { s -> ctx.observations.drop(s.sent.size) } ?: emptyList()
        val freshRendered = freshBatch.map { o -> OpenRouterBrain.toolName(o.call) to toolOutput(o, budget.toolChars) }
        val freshChars = freshRendered.sumOf { (name, body) -> (name.length + body.length).toLong() }
        val conversation: LmConversation
        val message: Message
        var usage: Long
        if (live != null && state != null && state.usage + freshChars <= budget.rolloverChars && canContinue(state, ctx, loaded)) {
            // The same turn going on: only the new batch is sent, as one tool message. The
            // model's own tool-call message is never re-sent. The live usage grows by what
            // this step adds: the batch sent now and the reply when it arrives.
            conversation = live.conversation
            usage = state.usage + freshChars
            message = Message.tool(Contents.of(freshRendered.map { (name, body) -> Content.ToolResponse(name, body) }))
        } else {
            // Rebuild from the full transcript. The run's previous conversation is closed first —
            // whatever its exact key was, since the task, the history or the thinking setting
            // may have changed — so a transcript that turns out too long leaves nothing open
            // behind it.
            session.closeRun(run)
            val built = transcript(ctx, loaded, budget)
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt(ctx, loaded)),
                initialMessages = built.initial,
                tools = if (loaded.toolCalling) toolProviders(ctx.memoryTool) else emptyList(),
                // The app executes tools itself, after the permission policy.
                automaticToolCalling = false,
            )
            conversation = session.open(
                key,
                Continuation(ctx.task, ctx.history, observationsOf(ctx, loaded), emptyList(), built.promptChars),
                config,
            )
            message = built.last
            usage = built.promptChars
        }
        val reply = try {
            stream(conversation, message, mapOf(ENABLE_THINKING to (loaded.thinking && thinking())), onReasoning)
        } catch (t: Throwable) {
            // An error or Stop ends the turn's use of the conversation.
            session.closeRun(run)
            throw t
        }
        // The reply itself is now in the conversation too: its text, its reasoning and its calls.
        usage += (reply.text.length + reply.thought.length +
            reply.calls.sumOf { c -> (c.name.length + CALL_OVERHEAD_CHARS + toJson(c.arguments).toString().length) }).toLong()

        val thought = reply.thought.trim().takeIf { it.isNotEmpty() }?.let { Thought(it) }
        if (reply.calls.isNotEmpty()) {
            val calls = reply.calls.mapIndexed { i, c ->
                OpenRouterBrain.parseToolCall("local_${ctx.observations.size + i + 1}", c.name, toJson(c.arguments).toString())
            }
            // The turn goes on: the stored state advances to what was just sent and asked, so the
            // next step continues from here instead of rebuilding against the original snapshot.
            // If no next step ever comes (the step limit, a Stop while a tool runs), endTurn
            // releases the conversation.
            session.advance(key, Continuation(ctx.task, ctx.history, observationsOf(ctx, loaded), calls, usage))
            return BrainDecision.Act(calls, thought)
        }
        // An answer ends the turn's use of the conversation.
        session.closeRun(run)
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
        conversation: LmConversation,
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
        /** Room always left for the answer: 1/8 of the context, clamped to 256..2000 tokens. */
        val answerTokens = (contextTokens / 8).coerceIn(256, 2_000)
        /** The hard limit on the whole prompt: the context minus the answer room, in characters. */
        val hardChars = ((contextTokens - answerTokens) * 4).toLong()
        /** Past this the live conversation is rolled over: about 2/3 of the context, in characters. */
        val rolloverChars = contextTokens * 4L * 2 / 3
    }

    /**
     * The turn's identity: run, model, settings, history and task. Two steps continue the same
     * conversation only when this matches; the observations are checked separately, so the key
     * never changes while a turn goes on.
     */
    private fun turnKey(ctx: BrainContext, loaded: LoadedModel): String = buildString {
        append(ctx.runId).append('|')
        append(loaded.modelId).append('|')
        append(loaded.backend).append('|')
        append(loaded.contextTokens).append('|')
        append(loaded.toolCalling).append('|')
        append(loaded.thinking && thinking()).append('|')
        append(ctx.memoryTool).append('|')
        append(ctx.task.length).append(':').append(ctx.task.hashCode()).append('|')
        ctx.history.forEach { append(it.role).append(':').append(it.text.hashCode()).append(',') }
    }

    /** What one observation contributes to the conversation, as it was sent. */
    private data class SentCall(
        val callId: String,
        val name: String,
        val args: String,
        val output: String,
        /** The result's error/exit-status info, as it shapes the rendered tool response. */
        val ok: Boolean,
        val exitCode: Int?,
        val batch: Int,
    )

    /**
     * The state a live conversation is continued from: the exact task and history (compared
     * exactly, not just through the turn-key hashes), the observations already sent, the model's
     * pending tool calls — the next step's fresh batch must answer exactly these, count and
     * calls both — and the running estimate of the live conversation's usage in characters.
     */
    private class Continuation(
        val task: String,
        val history: List<Turn>,
        val sent: List<SentCall>,
        val pending: List<ToolCall>,
        val usage: Long,
    )

    /**
     * The observations [ctx] would send into a conversation: nothing for a model without tools
     * (its transcript never contains them).
     */
    private fun observationsOf(ctx: BrainContext, loaded: LoadedModel): List<SentCall> =
        if (!loaded.toolCalling) emptyList() else ctx.observations.map { o ->
            SentCall(
                o.call.callId, OpenRouterBrain.toolName(o.call), OpenRouterBrain.arguments(o.call),
                o.result.output, o.result.ok, o.result.exitCode, o.batch,
            )
        }

    /**
     * Whether the observations are exactly what was already sent plus one new batch — and that
     * batch answers exactly the calls the model just made (same count, same call ids and
     * arguments). The task and the history are compared exactly too: anything the user changed
     * is a different conversation. Anything else (two new batches, a changed result, a changed
     * error status, a step back) rebuilds.
     */
    private fun canContinue(state: Continuation, ctx: BrainContext, loaded: LoadedModel): Boolean {
        if (state.task != ctx.task || state.history != ctx.history) return false
        val now = observationsOf(ctx, loaded)
        if (now.size <= state.sent.size) return false
        if (now.subList(0, state.sent.size) != state.sent) return false
        val fresh = now.drop(state.sent.size)
        if (fresh.any { it.batch != fresh.first().batch }) return false
        return ctx.observations.drop(state.sent.size).map { it.call } == state.pending
    }

    /**
     * The merged history+task turns and the observation batches after the soft trim, shared by
     * the transcript and the rollover count. Consecutive messages of the same role are merged,
     * because Gemma-style chat templates require user and model turns to alternate.
     */
    private fun softTrimmed(ctx: BrainContext, loaded: LoadedModel, budget: Budget): Pair<MutableList<Pair<Role, String>>, MutableList<List<Observation>>> {
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

        val batches = if (loaded.toolCalling) ctx.observations.groupBy { it.batch }.toSortedMap().values.toMutableList() else mutableListOf()
        fun estimate() = estimateChars(ctx, loaded, budget, merged, batches)
        // Drop the oldest history while the prompt does not fit (the task itself always stays).
        while (merged.size > 1 && estimate() > budget.promptChars) {
            merged.removeAt(0)
            while (merged.size > 1 && merged.first().first == Role.ASSISTANT) merged.removeAt(0)
        }
        return merged to batches
    }

    /**
     * One estimate of the whole prompt, in characters: the actual system prompt (schema sizes
     * counted only when they are sent), the kept history and the task, plus every batch's
     * tool-call arguments and outputs as they would be sent — the uncut latest outputs included.
     * The same estimate drives the soft trim, the hard size limit and the rollover count.
     */
    private fun estimateChars(
        ctx: BrainContext,
        loaded: LoadedModel,
        budget: Budget,
        history: List<Pair<Role, String>>,
        batches: List<List<Observation>>,
    ): Long =
        systemPromptLength(ctx, loaded).toLong() +
            history.sumOf { it.second.length.toLong() } +
            batches.sumOf { g -> g.sumOf { o -> (OpenRouterBrain.arguments(o.call).length + minOf(o.result.output.length, budget.toolChars)).toLong() } }

    /**
     * The conversation to replay ([first]) and the message to send now ([second]). Beyond the
     * soft trim this enforces a hard size limit — the context minus room for the answer — and
     * counts what the old trimming ignored: tool-call arguments and the uncut latest outputs.
     * When over the limit it drops the oldest history first, then the oldest complete
     * exchanges, each as calls plus results together; the task and the latest exchange always
     * stay. If it still does not fit, the step fails: the conversation is too long for this
     * model's context window.
     */
    private fun transcript(ctx: BrainContext, loaded: LoadedModel, budget: Budget): Built {
        val (merged, batches) = softTrimmed(ctx, loaded, budget)
        fun estimate() = estimateChars(ctx, loaded, budget, merged, batches)
        val tight = estimate() > budget.promptChars
        while (estimate() > budget.hardChars) {
            if (merged.size > 1) {
                merged.removeAt(0)
                while (merged.size > 1 && merged.first().first == Role.ASSISTANT) merged.removeAt(0)
            } else if (batches.size > 1) {
                batches.removeAt(0)
            } else {
                throw IOException("this conversation is too long for ${loaded.name}'s context window — start a new chat")
            }
        }

        val messages = ArrayList<Message>()
        merged.forEach { (role, text) -> messages += if (role == Role.USER) Message.user(text) else Message.model(text) }
        batches.forEachIndexed { i, group ->
            val latest = i == batches.lastIndex
            val limit = if (tight && !latest) budget.oldToolChars else budget.toolChars
            messages += Message.model(toolCalls = group.map { o -> LmToolCall(OpenRouterBrain.toolName(o.call), argumentsOf(o.call)) })
            messages += Message.tool(Contents.of(group.map { o -> Content.ToolResponse(OpenRouterBrain.toolName(o.call), toolOutput(o, limit)) }))
        }
        val last = messages.removeAt(messages.lastIndex)
        return Built(messages, last, estimate())
    }

    /** What the transcript built: the replay, the message to send now, and its size estimate. */
    private class Built(val initial: List<Message>, val last: Message, val promptChars: Long)

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

    /**
     * The size of what is actually sent: the system prompt text above, plus the tool schemas
     * only when tool calling is on, and the memory schema only with tools and memory both on.
     * Computed from the real prompt, so the two cannot drift.
     */
    private fun systemPromptLength(ctx: BrainContext, loaded: LoadedModel): Int =
        systemPrompt(ctx, loaded).length +
            (if (loaded.toolCalling) TOOL_SCHEMA_CHARS else 0) +
            (if (loaded.toolCalling && ctx.memoryTool) MEMORY_SCHEMA_CHARS else 0)

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
        /** Rough per-tool-call overhead of the chat template, for the live usage estimate. */
        private const val CALL_OVERHEAD_CHARS = 32
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
