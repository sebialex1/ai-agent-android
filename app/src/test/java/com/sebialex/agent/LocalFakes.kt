package com.sebialex.agent

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ToolCall
import com.sebialex.agent.data.local.LmConversation
import com.sebialex.agent.data.local.LmEngine
import com.sebialex.agent.data.local.LmRuntime
import com.sebialex.agent.data.local.ModelCapabilities
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plain-JVM fakes for the LiteRT-LM boundary ([LmRuntime]): conversations and engines that
 * count what was created, sent and closed, with scripted replies. Everything here runs
 * synchronously on the calling thread, so tests are deterministic.
 */
object LocalFakes {

    /** One scripted answer of the fake model: text, thought, tool calls, an error, or a hang. */
    class Script(
        val text: String = "ok",
        val thought: String = "",
        /** Tool name -> arguments as plain Kotlin values, exactly what the model would ask for. */
        val calls: List<Pair<String, Map<String, Any?>>> = emptyList(),
        val error: Throwable? = null,
        /** The model never answers: the caller is cancelled (Stop). */
        val hang: Boolean = false,
    )

    /** A model that answers with one tool call. */
    fun oneCall(name: String, args: Map<String, Any?> = emptyMap()) = Script(calls = listOf(name to args))

    /** A model that answers with text. */
    fun answer(text: String = "done") = Script(text = text)
}

/**
 * The fake native layer. Counts engines opened per backend, conversations created, model-info
 * reads and closes; conversations get their replies from [script], keyed by conversation and
 * send index.
 */
class FakeLmRuntime(
    /** Capabilities reported for every model file; null means "cannot read". */
    var caps: ModelCapabilities? = ModelCapabilities(functionCalling = true, thinking = false, speculativeDecoding = false, maxContext = null),
    /** Thrown when the GPU is asked for; null means the GPU works. */
    var gpuFailure: Throwable? = null,
    /** Thrown when the CPU is asked for; null means the CPU works. */
    var cpuFailure: Throwable? = null,
    /** Artificial latency of the model-info read, so loadMillis can be checked. */
    var modelInfoDelayMs: Long = 0,
    /** Artificial latency of every engine open, so loadMillis can be checked. */
    var openDelayMs: Long = 0,
    /** Artificial latency of every close, so cancellation during teardown can be checked. */
    var closeLagMs: Long = 0,
    /** The reply to the nth send of the nth conversation created. */
    val script: (conversation: Int, send: Int) -> LocalFakes.Script = { _, _ -> LocalFakes.answer() },
) : LmRuntime {
    val conversations = Collections.synchronizedList(mutableListOf<FakeLmConversation>())
    val engines = Collections.synchronizedList(mutableListOf<FakeLmEngine>())
    /** "conversation" and "engine" in the order closes ran. */
    val closeLog = Collections.synchronizedList(mutableListOf<String>())
    val gpuOpens = AtomicInteger()
    val cpuOpens = AtomicInteger()
    val modelInfoCalls = AtomicInteger()

    override fun modelInfo(path: String): ModelCapabilities? {
        if (modelInfoDelayMs > 0) Thread.sleep(modelInfoDelayMs)
        modelInfoCalls.incrementAndGet()
        return caps
    }

    override fun openEngine(path: String, gpu: Boolean, contextTokens: Int, speculative: Boolean, cacheDir: String): LmEngine {
        if (openDelayMs > 0) Thread.sleep(openDelayMs)
        if (gpu) {
            gpuOpens.incrementAndGet()
            gpuFailure?.let { throw it }
        } else {
            cpuOpens.incrementAndGet()
            cpuFailure?.let { throw it }
        }
        return FakeLmEngine(this).also { engines += it }
    }
}

/** A fake loaded model: hands out fake conversations and records its close. */
class FakeLmEngine(private val runtime: FakeLmRuntime) : LmEngine {
    val closed = AtomicBoolean()

    override fun createConversation(config: ConversationConfig): LmConversation {
        val conversation = FakeLmConversation(runtime, runtime.conversations.size, config)
        runtime.conversations += conversation
        return conversation
    }

    override fun close() {
        if (closeLagMs() > 0) Thread.sleep(closeLagMs())
        closed.set(true)
        runtime.closeLog += "engine"
    }

    private fun closeLagMs() = runtime.closeLagMs
}

/**
 * A fake conversation: records every message sent (the brain's own sends, not the initial
 * messages) and answers from the runtime's script. A hang never answers, so only a cancel
 * (Stop) ends the send.
 */
class FakeLmConversation(
    private val runtime: FakeLmRuntime,
    val index: Int,
    /** The config this conversation was created with. */
    val config: ConversationConfig,
) : LmConversation {
    val sent = Collections.synchronizedList(mutableListOf<Message>())
    val closed = AtomicBoolean()
    val cancelled = AtomicBoolean()
    private val callbacks = ConcurrentLinkedQueue<MessageCallback>()

    override fun sendMessageAsync(message: Message, callback: MessageCallback, extraContext: Map<String, Any>) {
        sent += message
        callbacks += callback
        val script = runtime.script(index, sent.size - 1)
        if (script.hang) return
        script.error?.let { e ->
            callback.onError(e)
            return
        }
        callback.onMessage(
            Message.model(
                contents = Contents.of(script.text),
                toolCalls = script.calls.map { (name, args) -> ToolCall(name, args) },
                channels = if (script.thought.isEmpty()) emptyMap() else mapOf("thought" to script.thought),
            ),
        )
        callback.onDone()
    }

    override fun cancelProcess() {
        cancelled.set(true)
        // The native layer reports a cancel as an error to the waiting callback.
        callbacks.forEach { it.onError(java.util.concurrent.CancellationException("stopped")) }
    }

    override fun close() {
        if (runtime.closeLagMs > 0) Thread.sleep(runtime.closeLagMs)
        closed.set(true)
        runtime.closeLog += "conversation"
    }
}
