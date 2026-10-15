package com.sebialex.agent.data.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo

/**
 * The fakeable boundary to LiteRT-LM. Everything that touches the native runtime — opening an
 * engine, reading a model file's self-description, sending a message, cancelling or closing —
 * goes through these interfaces, so plain-JVM tests can stand in for the native layer with
 * counting fakes. Only [LiteRtLmRuntime] knows the real classes.
 */
interface LmRuntime {
    /**
     * The model file's self-description, or null when it cannot be read. The real call reads
     * the file (LiteRT-LM issue #3878: it can be slow), so callers cache the result.
     */
    fun modelInfo(path: String): ModelCapabilities?

    /**
     * Opens an engine for the model at [path]. [gpu] selects the OpenCL backend; [speculative]
     * is multi-token prediction (only requested when the file says it has it). Throws when the
     * backend cannot start; the caller classifies the failure.
     */
    fun openEngine(path: String, gpu: Boolean, contextTokens: Int, speculative: Boolean, cacheDir: String): LmEngine
}

/** A loaded model: creates conversations and frees the model's memory on [close]. */
interface LmEngine {
    fun createConversation(config: ConversationConfig): LmConversation
    fun close()
}

/** One conversation of an engine. [sendMessageAsync] streams the answer through [MessageCallback]. */
interface LmConversation {
    fun sendMessageAsync(message: Message, callback: MessageCallback, extraContext: Map<String, Any>)
    fun cancelProcess()
    fun close()
}

/** The real LiteRT-LM: a stateless object over the AAR, safe to share. */
object LiteRtLmRuntime : LmRuntime {

    /** The native library is only loaded when a model is first opened, never at app start. */
    private var quietLogs = false

    override fun modelInfo(path: String): ModelCapabilities? = runCatching {
        ModelInfo.from(path).use { info ->
            val llm = info as? ModelInfo.Llm
            ModelCapabilities(
                functionCalling = llm?.let { runCatching { it.supportsFunctionCalling() }.getOrNull() },
                thinking = llm?.let { runCatching { it.supportsThinking() }.getOrNull() },
                speculativeDecoding = llm?.let { runCatching { it.hasSpeculativeDecodingSupport() }.getOrNull() },
                maxContext = runCatching { info.maxContextTokens() }.getOrNull(),
            )
        }
    }.getOrNull()

    @OptIn(ExperimentalApi::class)
    override fun openEngine(
        path: String,
        gpu: Boolean,
        contextTokens: Int,
        speculative: Boolean,
        cacheDir: String,
    ): LmEngine {
        if (!quietLogs) {
            quietLogs = true
            runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
        }
        // Read once by the Engine constructor/initialize; reset right after, as the flag is global.
        ExperimentalFlags.enableSpeculativeDecoding = speculative
        try {
            val e = Engine(
                EngineConfig(
                    modelPath = path,
                    backend = if (gpu) Backend.GPU() else Backend.CPU(),
                    maxNumTokens = contextTokens,
                    cacheDir = cacheDir,
                ),
            )
            try {
                e.initialize()
            } catch (t: Throwable) {
                runCatching { e.close() }
                throw t
            }
            return object : LmEngine {
                override fun createConversation(config: ConversationConfig): LmConversation {
                    val c = e.createConversation(config)
                    return object : LmConversation {
                        override fun sendMessageAsync(message: Message, callback: MessageCallback, extraContext: Map<String, Any>) =
                            c.sendMessageAsync(message, callback, extraContext)

                        override fun cancelProcess() = c.cancelProcess()
                        override fun close() {
                            runCatching { c.close() }
                        }
                    }
                }

                override fun close() {
                    runCatching { e.close() }
                }
            }
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = false
        }
    }
}
