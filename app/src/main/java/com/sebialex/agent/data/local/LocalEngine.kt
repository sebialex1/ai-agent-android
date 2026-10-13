package com.sebialex.agent.data.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.ModelInfo
import com.sebialex.agent.data.secure.SecureSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the model file says about itself (LiteRT-LM ModelInfo). Null fields: not reported. */
data class ModelCapabilities(
    val functionCalling: Boolean?,
    val thinking: Boolean?,
    val speculativeDecoding: Boolean?,
    val maxContext: Int?,
)

/** A model that is in memory and ready to answer. */
data class LoadedModel(
    val modelId: String,
    val name: String,
    /** "GPU" or "CPU": what actually runs, after any fallback. */
    val backend: String,
    /** Why the GPU was not used although it was requested, if that happened. */
    val fallbackReason: String?,
    val contextTokens: Int,
    val toolCalling: Boolean,
    val thinking: Boolean,
    val speculative: Boolean,
    val loadMillis: Long,
)

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val modelId: String, val name: String, val backend: String) : EngineState
    data class Ready(val model: LoadedModel) : EngineState
    data class Failed(val modelId: String, val name: String, val message: String) : EngineState
}

/**
 * Owns the single LiteRT-LM [Engine] of the app. At most one model is in memory at a time;
 * asking for another model (or a different backend, context size or MTP setting) closes the
 * current one first. Loading prefers the GPU (OpenCL) and falls back to the CPU when the GPU
 * path is unavailable, recording why. Every use goes through one mutex, so a conversation never
 * runs while the engine is being swapped or closed.
 */
class LocalEngine(
    private val store: ModelStore,
    private val settings: SecureSettings,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var key: Key? = null
    private var loaded: LoadedModel? = null
    private val caps = HashMap<String, ModelCapabilities>()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private data class Key(val path: String, val backend: String, val context: Int, val speculative: Boolean)

    /** The native library is only loaded when a model is first opened, never at app start. */
    private var quietLogs = false

    /** Capabilities read from the file at the last load, used for imported models. */
    fun capabilities(modelId: String): ModelCapabilities? = synchronized(caps) { caps[modelId] }

    /** Loads [model] (if it is not already loaded with the current settings) and returns it. */
    suspend fun load(model: LocalModel): LoadedModel = mutex.withLock { ensureLoaded(model) }

    /** Starts loading in the background, e.g. right after the user picks a model. */
    fun preload(model: LocalModel) {
        scope.launch { runCatching { load(model) } }
    }

    /**
     * Runs [block] with the loaded engine, holding the engine lock for the whole call so the
     * model cannot be unloaded or swapped underneath a running answer.
     */
    suspend fun <T> withEngine(model: LocalModel, block: suspend (Engine, LoadedModel) -> T): T = mutex.withLock {
        val info = ensureLoaded(model)
        block(engine ?: throw IOException("The on-device model is not loaded."), info)
    }

    /** Frees the model's memory. Waits for a running answer to finish first. */
    suspend fun unload() = mutex.withLock {
        closeLocked()
        _state.value = EngineState.Idle
    }

    fun unloadAsync() {
        scope.launch { unload() }
    }

    /** Unloads only if [modelId] is the model in memory (before deleting its file). */
    suspend fun unloadIf(modelId: String) = mutex.withLock {
        if (loaded?.modelId == modelId || (_state.value as? EngineState.Failed)?.modelId == modelId) {
            closeLocked()
            _state.value = EngineState.Idle
        }
    }

    private suspend fun ensureLoaded(model: LocalModel): LoadedModel {
        val file = store.fileFor(model)
            ?: throw IOException("${model.name} is not on this phone yet. Download it in Settings > On-device.")
        val wantGpu = settings.localBackend() == SecureSettings.BACKEND_GPU
        val requested = Key(
            path = file.absolutePath,
            backend = if (wantGpu) SecureSettings.BACKEND_GPU else SecureSettings.BACKEND_CPU,
            context = minOf(settings.localContext(), model.maxContext),
            speculative = settings.localSpeculative(),
        )
        val current = loaded
        if (engine != null && key == requested && current != null && current.modelId == model.id) return current

        closeLocked()
        _state.value = EngineState.Loading(model.id, model.name, if (wantGpu) "GPU" else "CPU")
        // Loading is a long blocking native call. It is never abandoned halfway: a cancelled
        // caller still waits for it, so the engine is either kept or closed, never leaked.
        return try {
            withContext(NonCancellable + Dispatchers.IO) { create(model, requested, wantGpu) }
        } catch (t: Throwable) {
            val message = describe(t)
            _state.value = EngineState.Failed(model.id, model.name, message)
            throw IOException("${model.name} could not be loaded: $message", t)
        }
    }

    private fun create(model: LocalModel, requested: Key, wantGpu: Boolean): LoadedModel {
        val info = readCapabilities(requested.path)
        synchronized(caps) { if (info != null) caps[model.id] = info }
        val maxFromFile = info?.maxContext?.takeIf { it > 0 }
        val context = if (maxFromFile != null) minOf(requested.context, maxFromFile) else requested.context
        // MTP must only be requested when the file has it; LiteRT-LM fails otherwise.
        val speculative = requested.speculative && info?.speculativeDecoding == true
        val started = System.currentTimeMillis()
        var fallback: String? = null
        val (created, backendName) = if (wantGpu) {
            try {
                open(requested.path, Backend.GPU(), context, speculative) to "GPU"
            } catch (t: Throwable) {
                fallback = describe(t)
                open(requested.path, Backend.CPU(), context, speculative = false) to "CPU"
            }
        } else {
            open(requested.path, Backend.CPU(), context, speculative = false) to "CPU"
        }
        val result = LoadedModel(
            modelId = model.id,
            name = model.name,
            backend = backendName,
            fallbackReason = fallback,
            contextTokens = context,
            toolCalling = if (model.imported) info?.functionCalling ?: true else model.toolCalling,
            thinking = if (model.imported) info?.thinking ?: false else model.thinking,
            speculative = speculative && backendName == "GPU",
            loadMillis = System.currentTimeMillis() - started,
        )
        engine = created
        key = requested
        loaded = result
        _state.value = EngineState.Ready(result)
        return result
    }

    @OptIn(ExperimentalApi::class)
    private fun open(path: String, backend: Backend, context: Int, speculative: Boolean): Engine {
        if (!quietLogs) {
            quietLogs = true
            runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
        }
        // Read once by the Engine constructor/initialize; reset right after, as the flag is global.
        ExperimentalFlags.enableSpeculativeDecoding = speculative
        try {
            val e = Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = context))
            try {
                e.initialize()
            } catch (t: Throwable) {
                runCatching { e.close() }
                throw t
            }
            return e
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = false
        }
    }

    private fun readCapabilities(path: String): ModelCapabilities? = runCatching {
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

    private fun closeLocked() {
        val e = engine
        engine = null
        key = null
        loaded = null
        if (e != null) runCatching { e.close() }
    }

    private fun describe(t: Throwable): String = when (t) {
        is UnsatisfiedLinkError -> "the on-device runtime is not available for this phone's processor"
        is OutOfMemoryError -> "not enough memory"
        else -> (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }?.take(240) ?: t.javaClass.simpleName
    }
}
