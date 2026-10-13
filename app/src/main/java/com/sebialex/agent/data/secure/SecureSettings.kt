package com.sebialex.agent.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sebialex.agent.data.llm.ReasoningPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Secrets and agent settings, stored with EncryptedSharedPreferences.
 * The AES-256 master key lives in the Android Keystore and never leaves secure hardware
 * when available. Keys are never logged, persisted elsewhere or rendered in the chat.
 */
class SecureSettings(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Snapshot(
        val hasTinyFishKey: Boolean,
        val tinyFishHint: String,
        val hasOpenRouterKey: Boolean,
        val openRouterHint: String,
        val hasOllamaKey: Boolean,
        val ollamaHint: String,
        /** Cloud provider shown in Settings and preselected in the picker: [PROVIDER_OPENROUTER] or [PROVIDER_OLLAMA]. */
        val cloudProvider: String,
        /** Last Ollama Cloud model id (with its `ollama/` prefix), empty when none was picked yet. */
        val ollamaModel: String,
        /** Ollama model name typed by hand (no prefix), for models the live list does not show. Empty when unset. */
        val ollamaCustomModel: String,
        val model: String,
        val askBeforeWeb: Boolean,
        /** Skip the Allow/Deny card and run risky agent actions immediately. Default off. */
        val autoApprove: Boolean,
        /** Reasoning choice stored for [model] only. Null fields mean the model default applies. */
        val reasoning: ReasoningPreference,
        /** Last OpenRouter model id, so switching back from an on-device model keeps it. */
        val remoteModel: String,
        /** On-device accelerator: [BACKEND_GPU] (default, falls back to CPU) or [BACKEND_CPU]. */
        val localBackend: String,
        /** On-device context window in tokens (prompt + answer). */
        val localContext: Int,
        /** Multi-token prediction for models that ship it (faster decoding on GPU). */
        val localSpeculative: Boolean,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Conversations with memory of past chats switched on. Kept as its own flow: the value is
     * per conversation and not part of [Snapshot], so a switch bound to [state] alone never saw
     * the change (equal snapshots are not re-emitted) and looked stuck.
     */
    private val _memoryChats = MutableStateFlow(
        prefs.all.keys.filter { it.startsWith(KEY_MEMORY_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(KEY_MEMORY_PREFIX) }.toSet(),
    )
    val memoryChats: StateFlow<Set<String>> = _memoryChats.asStateFlow()

    fun tinyFishKey(): String? = prefs.getString(KEY_TINYFISH, null)?.takeIf { it.isNotBlank() }
    fun openRouterKey(): String? = prefs.getString(KEY_OPENROUTER, null)?.takeIf { it.isNotBlank() }
    fun ollamaKey(): String? = prefs.getString(KEY_OLLAMA, null)?.takeIf { it.isNotBlank() }
    fun cloudProvider(): String = prefs.getString(KEY_PROVIDER, null)?.takeIf { it == PROVIDER_OLLAMA } ?: PROVIDER_OPENROUTER
    fun ollamaCustomModel(): String = prefs.getString(KEY_OLLAMA_CUSTOM, null)?.trim().orEmpty()
    fun ollamaModel(): String = prefs.getString(KEY_OLLAMA_MODEL, null)?.takeIf { it.startsWith(OLLAMA_PREFIX) }.orEmpty()
    fun model(): String = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun askBeforeWeb(): Boolean = prefs.getBoolean(KEY_ASK_WEB, false)
    fun autoApprove(): Boolean = prefs.getBoolean(KEY_AUTO_APPROVE, false)
    fun remoteModel(): String = prefs.getString(KEY_REMOTE_MODEL, null)?.takeIf { it.isNotBlank() }
        ?: model().takeUnless { it.startsWith(LOCAL_PREFIX) || it.startsWith(OLLAMA_PREFIX) } ?: DEFAULT_MODEL
    /** The model of the selected cloud provider: last Ollama model, or the last OpenRouter model. */
    fun cloudModel(): String = if (cloudProvider() == PROVIDER_OLLAMA) ollamaModel().ifEmpty { remoteModel() } else remoteModel()
    fun localBackend(): String = prefs.getString(KEY_LOCAL_BACKEND, null)?.takeIf { it == BACKEND_CPU } ?: BACKEND_GPU
    fun localContext(): Int = prefs.getInt(KEY_LOCAL_CONTEXT, DEFAULT_LOCAL_CONTEXT).takeIf { it in LOCAL_CONTEXTS } ?: DEFAULT_LOCAL_CONTEXT
    fun localSpeculative(): Boolean = prefs.getBoolean(KEY_LOCAL_SPECULATIVE, false)

    /** Reasoning on/off and effort are stored per model id, so each model keeps its own setting. */
    fun reasoning(model: String = model()): ReasoningPreference = ReasoningPreference(
        enabled = if (prefs.contains(reasoningOnKey(model))) prefs.getBoolean(reasoningOnKey(model), false) else null,
        effort = prefs.getString(reasoningEffortKey(model), null)?.takeIf { it.isNotBlank() },
    )

    fun setTinyFishKey(value: String?) = edit { putOrRemove(KEY_TINYFISH, value) }
    fun setOpenRouterKey(value: String?) = edit { putOrRemove(KEY_OPENROUTER, value) }
    fun setOllamaKey(value: String?) = edit { putOrRemove(KEY_OLLAMA, value) }
    /** Stores a hand-typed Ollama model name (already normalized, no prefix); empty clears it. */
    fun setOllamaCustomModel(value: String?) = edit { putOrRemove(KEY_OLLAMA_CUSTOM, value) }

    /**
     * Switches the cloud provider. When the active model belongs to the other cloud provider,
     * the last model used with [value] becomes active, so the switch really changes who answers.
     * An on-device model stays selected.
     */
    fun setCloudProvider(value: String) = edit {
        val p = if (value == PROVIDER_OLLAMA) PROVIDER_OLLAMA else PROVIDER_OPENROUTER
        putString(KEY_PROVIDER, p)
        val current = model()
        if (!current.startsWith(LOCAL_PREFIX)) {
            val next = if (p == PROVIDER_OLLAMA) ollamaModel() else remoteModel()
            if (next.isNotEmpty()) putString(KEY_MODEL, next)
        }
    }
    fun setModel(value: String?) = edit {
        putOrRemove(KEY_MODEL, value)
        // Remember the OpenRouter choice separately so the picker can switch back to it.
        val v = value?.trim()
        when {
            v.isNullOrEmpty() || v.startsWith(LOCAL_PREFIX) -> Unit
            v.startsWith(OLLAMA_PREFIX) -> { putString(KEY_OLLAMA_MODEL, v); putString(KEY_PROVIDER, PROVIDER_OLLAMA) }
            else -> { putString(KEY_REMOTE_MODEL, v); putString(KEY_PROVIDER, PROVIDER_OPENROUTER) }
        }
    }
    fun setLocalBackend(value: String) = edit { putString(KEY_LOCAL_BACKEND, if (value == BACKEND_CPU) BACKEND_CPU else BACKEND_GPU) }
    fun setLocalContext(value: Int) = edit { putInt(KEY_LOCAL_CONTEXT, value) }
    fun setLocalSpeculative(value: Boolean) = edit { putBoolean(KEY_LOCAL_SPECULATIVE, value) }
    /**
     * Memory of earlier conversations, stored per conversation and off by default. When on, the
     * agent gets the read-only `conversation_memory` tool in that conversation only.
     */
    fun conversationMemory(sessionId: String): Boolean = prefs.getBoolean(KEY_MEMORY_PREFIX + sessionId, false)
    fun setConversationMemory(sessionId: String, value: Boolean) {
        edit { if (value) putBoolean(KEY_MEMORY_PREFIX + sessionId, true) else remove(KEY_MEMORY_PREFIX + sessionId) }
        _memoryChats.value = if (value) _memoryChats.value + sessionId else _memoryChats.value - sessionId
    }
    /** Drops per-conversation settings of a deleted conversation. */
    fun forgetConversation(sessionId: String) {
        edit { remove(KEY_MEMORY_PREFIX + sessionId) }
        _memoryChats.value = _memoryChats.value - sessionId
    }
    fun setAskBeforeWeb(value: Boolean) = edit { putBoolean(KEY_ASK_WEB, value) }
    fun setAutoApprove(value: Boolean) = edit { putBoolean(KEY_AUTO_APPROVE, value) }
    fun setReasoningEnabled(model: String, value: Boolean) = edit { putBoolean(reasoningOnKey(model), value) }
    fun setReasoningEffort(model: String, value: String?) = edit { putOrRemove(reasoningEffortKey(model), value) }

    private fun reasoningOnKey(model: String) = "$KEY_REASONING_ON_PREFIX$model"
    private fun reasoningEffortKey(model: String) = "$KEY_REASONING_EFFORT_PREFIX$model"

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        val v = value?.trim()
        if (v.isNullOrEmpty()) remove(key) else putString(key, v)
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    private fun read() = Snapshot(
        hasTinyFishKey = tinyFishKey() != null,
        tinyFishHint = hint(tinyFishKey()),
        hasOpenRouterKey = openRouterKey() != null,
        openRouterHint = hint(openRouterKey()),
        hasOllamaKey = ollamaKey() != null,
        ollamaHint = hint(ollamaKey()),
        cloudProvider = cloudProvider(),
        ollamaModel = ollamaModel(),
        ollamaCustomModel = ollamaCustomModel(),
        model = model(),
        askBeforeWeb = askBeforeWeb(),
        autoApprove = autoApprove(),
        reasoning = reasoning(model()),
        remoteModel = remoteModel(),
        localBackend = localBackend(),
        localContext = localContext(),
        localSpeculative = localSpeculative(),
    )

    private fun hint(key: String?): String = if (key == null) "" else "\u2022\u2022\u2022\u2022 " + key.takeLast(4)

    companion object {
        private const val FILE_NAME = "agent_secure_prefs"
        private const val MASTER_KEY_ALIAS = "agent_master_key"
        private const val KEY_TINYFISH = "tinyfish_api_key"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_MODEL = "openrouter_model"
        private const val KEY_ASK_WEB = "ask_before_web"
        private const val KEY_AUTO_APPROVE = "auto_approve_actions"
        private const val KEY_REASONING_ON_PREFIX = "reasoning_enabled::"
        private const val KEY_REASONING_EFFORT_PREFIX = "reasoning_effort::"
        private const val KEY_OLLAMA = "ollama_api_key"
        private const val KEY_PROVIDER = "cloud_provider"
        private const val KEY_OLLAMA_MODEL = "ollama_model_last"
        private const val KEY_OLLAMA_CUSTOM = "ollama_model_custom"
        private const val KEY_MEMORY_PREFIX = "conversation_memory::"
        /** Same prefix as OllamaCloud.PREFIX. */
        private const val OLLAMA_PREFIX = "ollama/"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_OLLAMA = "ollama"
        private const val KEY_REMOTE_MODEL = "openrouter_model_last"
        private const val KEY_LOCAL_BACKEND = "local_backend"
        private const val KEY_LOCAL_CONTEXT = "local_context_tokens"
        private const val KEY_LOCAL_SPECULATIVE = "local_speculative_decoding"
        /** Same prefix as LocalModels.PREFIX; kept here so settings do not depend on the catalog. */
        private const val LOCAL_PREFIX = "local/"
        const val DEFAULT_MODEL = "openrouter/auto"
        const val BACKEND_GPU = "gpu"
        const val BACKEND_CPU = "cpu"
        const val DEFAULT_LOCAL_CONTEXT = 4096
        /** Context sizes offered in Settings. Larger windows need more memory and load slower. */
        val LOCAL_CONTEXTS = listOf(2048, 4096, 8192, 16384, 32000)
    }
}
