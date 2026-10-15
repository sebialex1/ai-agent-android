package com.sebialex.agent.data.local

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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
    /** The whole load: metadata, the GPU attempt and the CPU fallback. */
    val loadMillis: Long,
)

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val modelId: String, val name: String, val backend: String) : EngineState
    data class Ready(val model: LoadedModel) : EngineState
    data class Failed(val modelId: String, val name: String, val message: String) : EngineState
}

/**
 * Owns the single LiteRT-LM engine of the app. At most one model is in memory at a time;
 * asking for another model (or a different backend, context size or MTP setting) closes the
 * current one first. Loading prefers the GPU (OpenCL) and falls back to the CPU when the GPU
 * path is unavailable, recording why. Every use goes through one mutex, so a conversation never
 * runs while the engine is being swapped or closed.
 *
 * The engine also owns the live conversation of the turn that last used it — one slot. The
 * router builds a new [LocalBrain] per request, so the brain itself can hold no state; it asks
 * the engine for its conversation and continues it when the step really is the same turn going
 * on. Every close or load bumps [generation], so nothing survives a reload, and an ownership
 * change invalidates the previous slot: a late cleanup for one run never closes another run's
 * conversation.
 */
class LocalEngine(
    private val runtime: LmRuntime,
    private val files: (LocalModel) -> File?,
    private val cacheDir: File,
    private val backend: () -> String,
    private val contextTokens: () -> Int,
    private val speculative: () -> Boolean,
    private val scope: CoroutineScope,
) {
    constructor(store: ModelStore, settings: SecureSettings, scope: CoroutineScope) : this(
        runtime = LiteRtLmRuntime,
        files = { store.fileFor(it) },
        cacheDir = store.cacheDir,
        backend = settings::localBackend,
        contextTokens = settings::localContext,
        speculative = settings::localSpeculative,
        scope = scope,
    )

    private val mutex = Mutex()
    private var engine: LmEngine? = null
    private var key: Key? = null
    private var loaded: LoadedModel? = null
    private val caps = HashMap<String, ModelCapabilities>()

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private data class Key(val path: String, val backend: String, val context: Int, val speculative: Boolean)

    /** The engine's live conversation: what it was opened for and which engine generation owns it. */
    private class Slot(val conversation: LmConversation, val key: String, val state: Any?, val generation: Long)

    /** The one live conversation, or null. Replaced whole; see [closeLocked]. */
    private var slot: Slot? = null

    /** Bumped on every close or load, so nothing from a previous engine survives a reload. */
    private var generation = 0L

    /**
     * The GPU breaker: once the GPU environment is clearly missing, it is not tried again until
     * the app restarts. Memory failures, transient errors and model errors do not latch it.
     */
    @Volatile
    private var gpuBroken = false

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
     * model cannot be unloaded or swapped underneath a running answer. The [Session] handed to
     * [block] is the engine as one step of a turn sees it: its runtime handle plus the one
     * live conversation slot. It is only valid inside this call.
     */
    suspend fun <T> withEngine(model: LocalModel, block: suspend (LoadedModel, Session) -> T): T = mutex.withLock {
        val info = ensureLoaded(model)
        block(info, Session())
    }

    /**
     * Closes the live conversation of the turn whose key starts with [keyPrefix], however that
     * turn ended (answer, error, Stop). A conversation of another turn is never touched. Safe
     * to call after the caller was cancelled: the close itself always runs.
     */
    suspend fun closeTurn(keyPrefix: String) = withContext(NonCancellable) {
        mutex.withLock {
            val s = slot ?: return@withLock
            if (!s.key.startsWith(keyPrefix)) return@withLock
            slot = null
            withContext(Dispatchers.IO) { runCatching { s.conversation.close() } }
        }
    }

    /** Frees the model's memory. Waits for a running answer to finish first, and always runs. */
    suspend fun unload() = withContext(NonCancellable) {
        mutex.withLock {
            closeLocked()
            _state.value = EngineState.Idle
        }
    }

    fun unloadAsync() {
        scope.launch { unload() }
    }

    /** Unloads only if [modelId] is the model in memory (before deleting its file). */
    suspend fun unloadIf(modelId: String) = withContext(NonCancellable) {
        mutex.withLock {
            if (loaded?.modelId == modelId || (_state.value as? EngineState.Failed)?.modelId == modelId) {
                closeLocked()
                _state.value = EngineState.Idle
            }
        }
    }

    /**
     * The engine as one step of a turn sees it. Only valid inside [withEngine], which holds the
     * engine lock, so native operations stay serialized.
     */
    inner class Session {
        private val myGeneration = generation

        /**
         * The live conversation for [key], with the state its brain stored alongside it, when the
         * same turn is going on and no reload happened since. Null otherwise.
         */
        fun live(key: String): LiveConversation? {
            val s = slot ?: return null
            if (s.key != key || s.generation != myGeneration) return null
            return LiveConversation(s.conversation, s.state)
        }

        /**
         * Creates the turn's conversation and stores it in the slot. Whatever the slot held is
         * closed first: it cannot be continued after an ownership change.
         */
        suspend fun open(key: String, state: Any?, config: com.google.ai.edge.litertlm.ConversationConfig): LmConversation {
            closeCurrent()
            val conversation = (engine ?: throw IOException("The on-device model is not loaded.")).createConversation(config)
            slot = Slot(conversation, key, state, myGeneration)
            return conversation
        }

        /**
         * Closes the turn's conversation, but only if the slot still holds this turn's key: a
         * late cleanup for one run never closes another run's conversation. The close always
         * runs, even when the caller is already cancelled (Stop).
         */
        suspend fun close(key: String) {
            val s = slot ?: return
            if (s.key != key) return
            closeCurrent()
        }

        /**
         * Closes this run's conversation whatever its exact key was: the task, the history or
         * the thinking setting may have changed, and the old conversation must not survive a
         * rebuild. A conversation of another run is never touched.
         */
        suspend fun closeRun(runIdPrefix: String) {
            val s = slot ?: return
            if (!s.key.startsWith(runIdPrefix)) return
            closeCurrent()
        }

        /**
         * Replaces the stored state of the turn's conversation, but only while the slot still
         * holds this exact key and generation: a replaced or reloaded conversation is never
         * updated.
         */
        fun advance(key: String, state: Any?) {
            val s = slot ?: return
            if (s.key != key || s.generation != myGeneration) return
            slot = Slot(s.conversation, key, state, s.generation)
        }

        private suspend fun closeCurrent() {
            val s = slot ?: return
            slot = null
            withContext(NonCancellable + Dispatchers.IO) { runCatching { s.conversation.close() } }
        }
    }

    /** A live conversation of a turn, with whatever its brain stored alongside it. */
    class LiveConversation(val conversation: LmConversation, val state: Any?)

    private suspend fun ensureLoaded(model: LocalModel): LoadedModel {
        val file = files(model)
            ?: throw IOException("${model.name} is not on this phone yet. Download it in Settings > On-device.")
        val wantGpu = backend() == SecureSettings.BACKEND_GPU
        val requested = Key(
            path = file.absolutePath,
            backend = if (wantGpu) SecureSettings.BACKEND_GPU else SecureSettings.BACKEND_CPU,
            context = minOf(contextTokens(), model.maxContext),
            speculative = speculative(),
        )
        val current = loaded
        if (engine != null && key == requested && current != null && current.modelId == model.id) return current

        closeLocked()
        _state.value = EngineState.Loading(model.id, model.name, if (wantGpu) "GPU" else "CPU")
        // Loading is a long blocking native call. It is never abandoned halfway: a cancelled
        // caller still waits for it, so the engine is either kept or closed, never leaked.
        return try {
            withContext(NonCancellable + Dispatchers.IO) { create(model, file, requested, wantGpu) }
        } catch (t: Throwable) {
            val message = if (t is NotEnoughMemory) "not enough memory" else describe(t)
            _state.value = EngineState.Failed(model.id, model.name, message)
            throw IOException("${model.name} could not be loaded: $message", t)
        }
    }

    private fun create(model: LocalModel, file: File, requested: Key, wantGpu: Boolean): LoadedModel {
        // The whole load is timed: metadata, the GPU attempt and the CPU fallback.
        val started = System.currentTimeMillis()
        val info = readCapabilities(file)
        synchronized(caps) { if (info != null) caps[model.id] = info }
        val maxFromFile = info?.maxContext?.takeIf { it > 0 }
        val context = if (maxFromFile != null) minOf(requested.context, maxFromFile) else requested.context
        // MTP must only be requested when the file has it; LiteRT-LM fails otherwise.
        val speculative = requested.speculative && info?.speculativeDecoding == true
        var fallback: String? = null
        val (created, backendName) = when {
            !wantGpu -> openCpu(requested.path, context) to "CPU"
            gpuBroken -> {
                fallback = "the GPU stays off for this app run: it failed to start before"
                openCpu(requested.path, context) to "CPU"
            }
            else -> {
                try {
                    runtime.openEngine(requested.path, gpu = true, context, speculative, cacheDir.absolutePath) to "GPU"
                } catch (t: Throwable) {
                    when (classifyGpuFailure(t)) {
                        GpuFailure.MEMORY ->
                            // Not enough memory for the GPU: the load fails, no CPU engine is
                            // opened, and the breaker is not tripped, so Retry tries the GPU again.
                            throw NotEnoughMemory(t)
                        GpuFailure.PERSISTENT -> {
                            // The GPU environment is clearly missing: fall back and stop trying.
                            gpuBroken = true
                            fallback = describe(t)
                            openCpu(requested.path, context) to "CPU"
                        }
                        GpuFailure.TRANSIENT -> {
                            // Unsupported operation, kernel compilation, anything unclear: fall
                            // back for this load only; the next load tries the GPU again.
                            fallback = describe(t)
                            openCpu(requested.path, context) to "CPU"
                        }
                    }
                }
            }
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
        generation++
        _state.value = EngineState.Ready(result)
        return result
    }

    private fun openCpu(path: String, context: Int): LmEngine = runtime.openEngine(path, gpu = false, context, speculative = false, cacheDir.absolutePath)

    /**
     * Closes the live conversation (first) and the engine (second). Both always run, even when
     * the caller is already cancelled, and run on [Dispatchers.IO] so a model swap started from
     * a settings screen never closes native memory on the main thread. The whole sequence is one
     * non-cancellable region — the generation bump, the detach and both closes — each close
     * individually wrapped so a throwing conversation close cannot skip the engine close.
     * Bumping [generation] invalidates the slot before anything is closed.
     */
    private suspend fun closeLocked() = withContext(NonCancellable + Dispatchers.IO) {
        generation++
        val s = slot
        slot = null
        val e = engine
        engine = null
        key = null
        loaded = null
        if (s != null) runCatching { s.conversation.close() }
        if (e != null) runCatching { e.close() }
    }

    // ------------------------------------------------------------------ capabilities cache

    /** A cache key: the same bytes at the same path. */
    private data class CapsKey(val path: String, val size: Long, val modified: Long)

    private val capsLock = Any()
    private val capsByFile = HashMap<CapsKey, ModelCapabilities>()
    private var persistedCaps: MutableList<CapsEntry>? = null

    /** The persisted cache file, so the first load after a restart skips the read (issue #3878). */
    private val capsFile: File get() = File(cacheDir, "model_capabilities.json")

    /**
     * The model's capabilities, cached by path, size and modified time — a matching file skips
     * the [ModelInfo] read entirely.
     */
    private fun readCapabilities(file: File): ModelCapabilities? {
        val k = CapsKey(file.absolutePath, file.length(), file.lastModified())
        synchronized(capsLock) {
            capsByFile[k]?.let { return it }
            (persistedCaps ?: loadPersistedCaps())?.firstOrNull {
                it.path == k.path && it.size == k.size && it.modified == k.modified
            }?.let { entry ->
                val caps = entry.toCaps()
                capsByFile[k] = caps
                return caps
            }
        }
        val info = runtime.modelInfo(file.absolutePath) ?: return null
        synchronized(capsLock) {
            capsByFile[k] = info
            val entries = persistedCaps ?: mutableListOf<CapsEntry>().also { persistedCaps = it }
            entries.removeAll { it.path == k.path }
            entries += k.toEntry(info)
            // Keep the file bounded: the most recent models only.
            while (entries.size > MAX_CACHED_MODELS) entries.removeAt(0)
            persistCaps()
        }
        return info
    }

    private fun loadPersistedCaps(): MutableList<CapsEntry> {
        val entries = runCatching {
            CAPS_JSON.decodeFromString(ListSerializer(CapsEntry.serializer()), capsFile.readText())
        }.getOrDefault(emptyList()).toMutableList()
        persistedCaps = entries
        return entries
    }

    private fun persistCaps() {
        val entries = persistedCaps ?: return
        runCatching { capsFile.parentFile?.mkdirs(); capsFile.writeText(CAPS_JSON.encodeToString(ListSerializer(CapsEntry.serializer()), entries)) }
    }

    @Serializable
    private data class CapsEntry(
        val path: String,
        val size: Long,
        val modified: Long,
        val functionCalling: Boolean? = null,
        val thinking: Boolean? = null,
        val speculativeDecoding: Boolean? = null,
        val maxContext: Int? = null,
    )

    private fun CapsEntry.toCaps() = ModelCapabilities(functionCalling, thinking, speculativeDecoding, maxContext)

    private fun CapsKey.toEntry(info: ModelCapabilities) = CapsEntry(
        path, size, modified, info.functionCalling, info.thinking, info.speculativeDecoding, info.maxContext,
    )

    // ------------------------------------------------------------------ GPU failure classification

    /** How a GPU failure should be treated. */
    internal enum class GpuFailure { MEMORY, PERSISTENT, TRANSIENT }

    /** Raised for a GPU out-of-memory: the load fails with "not enough memory", no CPU engine. */
    private class NotEnoughMemory(cause: Throwable) : Exception(cause)

    /**
     * Classifies a failure of the GPU attempt. Memory failures are checked first: they fail the
     * load instead of falling through to the CPU engine. [UnsatisfiedLinkError] anywhere in the
     * cause chain is always persistent; otherwise a message only latches the breaker on clear
     * missing-GPU-environment evidence, and never when it also names the model's own data.
     */
    internal fun classifyGpuFailure(t: Throwable): GpuFailure = when {
        isMemoryFailure(t) -> GpuFailure.MEMORY
        isPersistentEnvironment(t) -> GpuFailure.PERSISTENT
        else -> GpuFailure.TRANSIENT
    }

    private fun isMemoryFailure(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is OutOfMemoryError) return true
            val m = cause.message?.lowercase()
            if (m != null && MEMORY_HINTS.any { m.contains(it) }) return true
            cause = cause.cause
        }
        return false
    }

    private fun isPersistentEnvironment(t: Throwable): Boolean {
        var cause: Throwable? = t
        val message = StringBuilder()
        while (cause != null) {
            if (cause is UnsatisfiedLinkError) return true
            cause.message?.let { message.append(it).append('\n') }
            cause = cause.cause
        }
        val m = message.toString().lowercase()
        // A message that also names the model's own data is about the model, not the environment.
        if (MODEL_WORDS.any { m.contains(it) }) return false
        return GPU_ENVIRONMENT_HINTS.any { m.contains(it) }
    }

    private fun describe(t: Throwable): String = when (t) {
        is UnsatisfiedLinkError -> "the on-device runtime is not available for this phone's processor"
        is OutOfMemoryError -> "not enough memory"
        is NotEnoughMemory -> "not enough memory"
        else -> (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }?.take(240) ?: t.javaClass.simpleName
    }

    companion object {
        /** Most recent models kept in model_capabilities.json. */
        private const val MAX_CACHED_MODELS = 32
        private val CAPS_JSON = Json { ignoreUnknownKeys = true }

        /** Allocation and OpenCL memory/resource errors: a GPU out of memory, not a broken environment. */
        private val MEMORY_HINTS = listOf(
            "out of memory", "not enough memory", "insufficient memory",
            "cl_mem_object_allocation_failure", "cl_out_of_resources", "cl_out_of_host_memory",
            "allocation failure", "failed to allocate",
            "cannot allocate", "unable to allocate", "memory allocation", "resource exhaustion",
        )

        /**
         * Words that name the model's own data. A message carrying any of them is about the
         * model, not the GPU environment, and never latches the breaker.
         */
        private val MODEL_WORDS = listOf("model", "tensor", "shape", "signature", "section", "metadata", "schema")

        /**
         * Phrases saying the GPU environment is missing or could not start. Only these latch the
         * breaker; anything else (an unsupported operation, a kernel compilation failure, a
         * transient error) falls back to the CPU for that load only.
         */
        private val GPU_ENVIRONMENT_HINTS = listOf(
            "libopencl", "no opencl", "opencl not", "opencl unavailable",
            "cannot open opencl", "can not open opencl", "failed to load opencl",
            "failed to initialize opencl environment", "failed to create opencl environment",
            "failed to create opencl context",
            "gpu is not supported", "gpu not supported",
            "no gpu found", "no gpu available", "no gpu device",
            "failed to create gpu environment", "failed to initialize gpu environment",
            "gpu backend is not available", "gpu backend not available",
            "gpu environment is not available", "gpu environment not available",
        )
    }
}
