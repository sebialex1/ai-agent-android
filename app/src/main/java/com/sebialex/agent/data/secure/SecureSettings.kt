package com.sebialex.agent.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sebialex.agent.data.llm.ReasoningPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Secrets and agent settings, stored with EncryptedSharedPreferences.
 * The AES-256 master key lives in the Android Keystore and never leaves secure hardware
 * when available. Keys are never logged, persisted elsewhere or rendered in the chat.
 */
class SecureSettings(context: Context) : com.sebialex.agent.domain.agent.KeyPool {

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
        /** What new conversations start with: on unless the user explicitly saved "off". */
        val memoryForNewChats: Boolean = true,
        /** Let the model write conversation titles. */
        val autoTitles: Boolean = true,
        /** Every provider's keys (masked), by provider id; see [KeyPool]. */
        val keys: Map<String, List<KeyView>> = emptyMap(),
        /** User-added OpenAI-compatible providers. */
        val customProviders: List<CustomProvider> = emptyList(),
        /** generate_image's model as saved; null while the default ([MediaModels.DEFAULT]) applies. */
        val mediaImage: com.sebialex.agent.data.llm.MediaModelRef? = null,
    )

    /** One stored key as the UI may show it: never the key itself, only its last characters. */
    data class KeyView(val id: String, val hint: String, val label: String, val active: Boolean, val failing: Boolean, val addedAt: Long)

    // Declared before _state: read() (its initial value) already uses them.
    private val keyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Keys that failed recently (provider:key to when), in memory only: a restart retries them. */
    private val failing = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    // The active key of each built-in provider (several keys can be stored; see the key ring below).
    fun tinyFishKey(): String? = activeKey(PROVIDER_TINYFISH)
    fun openRouterKey(): String? = activeKey(PROVIDER_OPENROUTER)
    fun ollamaKey(): String? = activeKey(PROVIDER_OLLAMA)
    fun cloudProvider(): String = prefs.getString(KEY_PROVIDER, null)?.takeIf { it == PROVIDER_OLLAMA } ?: PROVIDER_OPENROUTER
    fun ollamaCustomModel(): String = prefs.getString(KEY_OLLAMA_CUSTOM, null)?.trim().orEmpty()
    fun ollamaModel(): String = prefs.getString(KEY_OLLAMA_MODEL, null)?.takeIf { it.startsWith(OLLAMA_PREFIX) }.orEmpty()
    fun model(): String = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun askBeforeWeb(): Boolean = prefs.getBoolean(KEY_ASK_WEB, false)
    fun autoApprove(): Boolean = prefs.getBoolean(KEY_AUTO_APPROVE, false)
    fun remoteModel(): String = prefs.getString(KEY_REMOTE_MODEL, null)?.takeIf { it.isNotBlank() }
        ?: model().takeUnless { it.startsWith(LOCAL_PREFIX) || it.startsWith(OLLAMA_PREFIX) || it.startsWith(CUSTOM_MODEL_PREFIX) } ?: DEFAULT_MODEL
    /** The model of the selected cloud provider: last Ollama model, or the last OpenRouter model. */
    fun cloudModel(): String = if (cloudProvider() == PROVIDER_OLLAMA) ollamaModel().ifEmpty { remoteModel() } else remoteModel()
    fun localBackend(): String = prefs.getString(KEY_LOCAL_BACKEND, null)?.takeIf { it == BACKEND_CPU } ?: BACKEND_GPU
    fun localContext(): Int = prefs.getInt(KEY_LOCAL_CONTEXT, DEFAULT_LOCAL_CONTEXT).takeIf { it in LOCAL_CONTEXTS } ?: DEFAULT_LOCAL_CONTEXT
    fun localSpeculative(): Boolean = prefs.getBoolean(KEY_LOCAL_SPECULATIVE, false)
    // On by default only while unset: an explicitly saved "off" (memory or titles) is always kept.
    fun memoryForNewChats(): Boolean = prefs.getBoolean(KEY_MEMORY_NEW_CHATS, true)
    fun autoTitles(): Boolean = prefs.getBoolean(KEY_AUTO_TITLES, true)
    fun setMemoryForNewChats(value: Boolean) = edit { putBoolean(KEY_MEMORY_NEW_CHATS, value) }
    fun setAutoTitles(value: Boolean) = edit { putBoolean(KEY_AUTO_TITLES, value) }

    /** Reasoning on/off and effort are stored per model id, so each model keeps its own setting. */
    fun reasoning(model: String = model()): ReasoningPreference = ReasoningPreference(
        enabled = if (prefs.contains(reasoningOnKey(model))) prefs.getBoolean(reasoningOnKey(model), false) else null,
        effort = prefs.getString(reasoningEffortKey(model), null)?.takeIf { it.isNotBlank() },
    )

    /**
     * The image model generate_image uses: provider (`openrouter` or `custom:<id>`) plus model id,
     * stored together so equal ids on different providers never collide. [MediaModels.DEFAULT]
     * until the user picks one (what the app used before the picker existed).
     */
    fun mediaImageModel(): com.sebialex.agent.data.llm.MediaModelRef = storedMediaImageModel() ?: com.sebialex.agent.data.llm.MediaModels.DEFAULT

    /** The saved choice, or null while the default applies. */
    fun storedMediaImageModel(): com.sebialex.agent.data.llm.MediaModelRef? {
        val provider = prefs.getString(KEY_MEDIA_IMAGE_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: return null
        val model = prefs.getString(KEY_MEDIA_IMAGE_MODEL, null)?.takeIf { it.isNotBlank() } ?: return null
        return com.sebialex.agent.data.llm.MediaModelRef(provider, model)
    }

    /** Saves (or with null clears, back to the default) the image model. */
    fun setMediaImageModel(ref: com.sebialex.agent.data.llm.MediaModelRef?) = edit {
        if (ref == null) { remove(KEY_MEDIA_IMAGE_PROVIDER); remove(KEY_MEDIA_IMAGE_MODEL) }
        else { putString(KEY_MEDIA_IMAGE_PROVIDER, ref.provider); putString(KEY_MEDIA_IMAGE_MODEL, ref.model) }
    }

    /** Adds a key (null or blank removes every key of that provider), kept for older call sites. */
    fun setTinyFishKey(value: String?) = setSingle(PROVIDER_TINYFISH, value)
    fun setOpenRouterKey(value: String?) = setSingle(PROVIDER_OPENROUTER, value)
    fun setOllamaKey(value: String?) = setSingle(PROVIDER_OLLAMA, value)

    private fun setSingle(provider: String, value: String?) {
        if (value.isNullOrBlank()) clearKeys(provider) else addKey(provider, value)
    }

    // ---- Key ring: several keys per provider, one active, rotation on failure -----------------

    @kotlinx.serialization.Serializable
    data class StoredKey(val id: String, val key: String, val label: String = "", val addedAt: Long = 0)

    /** The stored keys of [provider], oldest first. A key saved by an older version is migrated in. */
    @Synchronized
    fun storedKeys(provider: String): List<StoredKey> {
        val raw = prefs.getString(KEY_RING_PREFIX + provider, null)
        if (raw != null) return runCatching { keyJson.decodeFromString<List<StoredKey>>(raw) }.getOrDefault(emptyList())
        val legacy = LEGACY_KEYS[provider]?.let { prefs.getString(it, null) }?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val migrated = listOf(StoredKey(newKeyId(), legacy, "", System.currentTimeMillis()))
        prefs.edit().putString(KEY_RING_PREFIX + provider, keyJson.encodeToString(migrated)).apply()
        return migrated
    }

    private fun writeKeys(provider: String, keys: List<StoredKey>) = edit {
        if (keys.isEmpty()) remove(KEY_RING_PREFIX + provider) else putString(KEY_RING_PREFIX + provider, keyJson.encodeToString(keys))
        // The single legacy entry is never read again once the ring exists; drop it with the last key.
        LEGACY_KEYS[provider]?.let { if (keys.isEmpty()) remove(it) }
        if (keys.none { it.id == prefs.getString(KEY_ACTIVE_PREFIX + provider, null) }) {
            if (keys.isEmpty()) remove(KEY_ACTIVE_PREFIX + provider) else putString(KEY_ACTIVE_PREFIX + provider, keys.first().id)
        }
    }

    /** Adds [key] to [provider] (a duplicate is ignored). The first key becomes active. */
    @Synchronized
    fun addKey(provider: String, key: String, label: String = "") {
        val k = key.trim()
        if (k.isEmpty()) return
        val keys = storedKeys(provider)
        if (keys.any { it.key == k }) return
        writeKeys(provider, keys + StoredKey(newKeyId(), k, label.trim().take(40), System.currentTimeMillis()))
    }

    @Synchronized
    fun removeKey(provider: String, id: String) {
        val keys = storedKeys(provider)
        keys.firstOrNull { it.id == id }?.let { failing.remove(provider + ":" + it.key) }
        writeKeys(provider, keys.filterNot { it.id == id })
    }

    @Synchronized
    fun clearKeys(provider: String) {
        storedKeys(provider).forEach { failing.remove(provider + ":" + it.key) }
        writeKeys(provider, emptyList())
    }

    /** Makes the key [id] the one used from now on (and clears its failing mark). */
    @Synchronized
    fun setActiveKey(provider: String, id: String) {
        val key = storedKeys(provider).firstOrNull { it.id == id } ?: return
        failing.remove(provider + ":" + key.key)
        edit { putString(KEY_ACTIVE_PREFIX + provider, id) }
    }

    @Synchronized
    override fun activeKey(provider: String): String? {
        val keys = storedKeys(provider)
        if (keys.isEmpty()) return null
        val id = prefs.getString(KEY_ACTIVE_PREFIX + provider, null)
        return (keys.firstOrNull { it.id == id } ?: keys.first()).key
    }

    override fun keyCount(provider: String): Int = storedKeys(provider).size

    @Synchronized
    override fun rotateKey(provider: String, failed: String, reason: String): String? {
        val now = System.currentTimeMillis()
        failing[provider + ":" + failed] = now
        val keys = storedKeys(provider)
        if (keys.size < 2) { publishKeys(); return null }
        val start = keys.indexOfFirst { it.key == failed }.coerceAtLeast(0)
        // The next key after the failed one (wrapping around) that has not failed recently.
        val next = (1 until keys.size).map { keys[(start + it) % keys.size] }
            .firstOrNull { k -> (failing[provider + ":" + k.key] ?: 0L) < now - KEY_COOLDOWN_MS }
        if (next == null) { publishKeys(); return null }
        edit { putString(KEY_ACTIVE_PREFIX + provider, next.id) }
        return next.key
    }

    override fun markKeyOk(provider: String, key: String) {
        if (failing.remove("$provider:$key") != null) publishKeys()
    }

    private fun publishKeys() { _state.value = read() }

    private fun keyViews(): Map<String, List<KeyView>> {
        val providers = listOf(PROVIDER_OPENROUTER, PROVIDER_OLLAMA, PROVIDER_TINYFISH) +
            customProviders().map { com.sebialex.agent.domain.agent.KeyPool.CUSTOM_PREFIX + it.id }
        val now = System.currentTimeMillis()
        return providers.associateWith { p ->
            val keys = storedKeys(p)
            val activeId = prefs.getString(KEY_ACTIVE_PREFIX + p, null)?.takeIf { id -> keys.any { it.id == id } } ?: keys.firstOrNull()?.id
            keys.map { k ->
                KeyView(
                    id = k.id, hint = hint(k.key), label = k.label, active = k.id == activeId,
                    failing = (failing["$p:${k.key}"] ?: 0L) >= now - KEY_COOLDOWN_MS, addedAt = k.addedAt,
                )
            }
        }
    }

    private fun newKeyId() = java.lang.Long.toString(System.nanoTime(), 36) + java.lang.Long.toString((Math.random() * 46656).toLong(), 36)

    // ---- Backup support -------------------------------------------------------------------------

    /** Every non-secret preference (models, switches, custom providers, per-model reasoning, ...). */
    @Synchronized
    fun exportPlain(): Map<String, Any?> =
        prefs.all.filterKeys { !com.sebialex.agent.data.backup.BackupBundle.isSecretPref(it) }

    /** Writes backed-up preferences over the current ones. Secrets in [values] are ignored. */
    @Synchronized
    fun importPlain(values: Map<String, Any>) {
        edit {
            values.forEach { (k, v) ->
                if (com.sebialex.agent.data.backup.BackupBundle.isSecretPref(k)) return@forEach
                when (v) {
                    is Boolean -> putBoolean(k, v)
                    is Int -> putInt(k, v)
                    is String -> putString(k, v)
                }
            }
        }
        _memoryChats.value = prefs.all.keys.filter { it.startsWith(KEY_MEMORY_PREFIX) && prefs.getBoolean(it, false) }
            .map { it.removePrefix(KEY_MEMORY_PREFIX) }.toSet()
    }

    /** All key rings for an encrypted backup. */
    @Synchronized
    fun exportSecrets(): com.sebialex.agent.data.backup.BackupBundle.Secrets {
        val providers = listOf(PROVIDER_OPENROUTER, PROVIDER_OLLAMA, PROVIDER_TINYFISH) +
            customProviders().map { com.sebialex.agent.domain.agent.KeyPool.CUSTOM_PREFIX + it.id }
        return com.sebialex.agent.data.backup.BackupBundle.Secrets(
            providers.associateWith { p ->
                val active = activeKey(p)
                storedKeys(p).map { com.sebialex.agent.data.backup.BackupBundle.SecretKey(it.key, it.label, it.addedAt, it.key == active) }
            }.filterValues { it.isNotEmpty() },
        )
    }

    /** Merges backed-up keys in (duplicates are ignored); a backed-up active key becomes active if the provider had none. */
    @Synchronized
    fun importSecrets(secrets: com.sebialex.agent.data.backup.BackupBundle.Secrets) {
        secrets.keys.forEach { (provider, list) ->
            val hadKeys = storedKeys(provider).isNotEmpty()
            list.forEach { addKey(provider, it.key, it.label) }
            if (!hadKeys) list.firstOrNull { it.active }?.let { a ->
                storedKeys(provider).firstOrNull { it.key == a.key }?.let { setActiveKey(provider, it.id) }
            }
        }
    }

    /** Passphrase for encrypting API keys in backups. Lives in the Keystore-backed store only, so it never leaves the phone. */
    fun backupPassphrase(): String? = prefs.getString(KEY_BACKUP_PASSPHRASE, null)?.takeIf { it.isNotEmpty() }
    fun setBackupPassphrase(value: String?) = prefs.edit().apply {
        if (value.isNullOrEmpty()) remove(KEY_BACKUP_PASSPHRASE) else putString(KEY_BACKUP_PASSPHRASE, value)
    }.apply()

    /** True while nothing was ever configured: no keys, no model chosen, no custom providers. */
    fun isPristine(): Boolean = prefs.all.isEmpty()

    // ---- Custom OpenAI-compatible providers ----------------------------------------------------

    @kotlinx.serialization.Serializable
    data class CustomProvider(
        val id: String,
        val name: String,
        /** OpenAI-style API root, e.g. https://api.groq.com/openai/v1 (no trailing slash). */
        val baseUrl: String,
        /** Preset it was created from, or "custom". */
        val preset: String = "custom",
        /** Model ids found at `/models` (or typed by hand). */
        val models: List<String> = emptyList(),
        /** What auto-detection found, shown under the name. */
        val detected: String = "",
        /** Capability metadata from the provider's own model list (image output, reasoning), by model id. */
        val modelInfo: Map<String, com.sebialex.agent.data.llm.ProviderModelMeta> = emptyMap(),
    )

    fun customProviders(): List<CustomProvider> =
        prefs.getString(KEY_CUSTOM_PROVIDERS, null)?.let { raw ->
            runCatching { keyJson.decodeFromString<List<CustomProvider>>(raw) }.getOrNull()
        }.orEmpty()

    fun customProvider(id: String): CustomProvider? = customProviders().firstOrNull { it.id == id }

    /** Adds or replaces (same id) a custom provider. */
    @Synchronized
    fun saveCustomProvider(provider: CustomProvider) {
        val list = customProviders().filterNot { it.id == provider.id } + provider
        edit { putString(KEY_CUSTOM_PROVIDERS, keyJson.encodeToString(list)) }
    }

    /** Removes a custom provider and its keys; a selected model of it falls back to the default. */
    @Synchronized
    fun removeCustomProvider(id: String) {
        clearKeys(com.sebialex.agent.domain.agent.KeyPool.CUSTOM_PREFIX + id)
        val list = customProviders().filterNot { it.id == id }
        edit {
            putString(KEY_CUSTOM_PROVIDERS, keyJson.encodeToString(list))
            if (model().startsWith("$CUSTOM_MODEL_PREFIX$id/")) putString(KEY_MODEL, remoteModel())
        }
    }
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
            v.isNullOrEmpty() || v.startsWith(LOCAL_PREFIX) || v.startsWith(CUSTOM_MODEL_PREFIX) -> Unit
            v.startsWith(OLLAMA_PREFIX) -> { putString(KEY_OLLAMA_MODEL, v); putString(KEY_PROVIDER, PROVIDER_OLLAMA) }
            else -> { putString(KEY_REMOTE_MODEL, v); putString(KEY_PROVIDER, PROVIDER_OPENROUTER) }
        }
    }
    fun setLocalBackend(value: String) = edit { putString(KEY_LOCAL_BACKEND, if (value == BACKEND_CPU) BACKEND_CPU else BACKEND_GPU) }
    fun setLocalContext(value: Int) = edit { putInt(KEY_LOCAL_CONTEXT, value) }
    fun setLocalSpeculative(value: Boolean) = edit { putBoolean(KEY_LOCAL_SPECULATIVE, value) }
    /**
     * Memory of earlier conversations, stored per conversation. New chats start with
     * [memoryForNewChats] (on until the user saves "off"); when on, the agent gets the read-only `conversation_memory` tool in
     * that conversation only.
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
        memoryForNewChats = memoryForNewChats(),
        autoTitles = autoTitles(),
        keys = keyViews(),
        customProviders = customProviders(),
        mediaImage = storedMediaImageModel(),
    )

    private fun hint(key: String?): String = if (key == null) "" else "\u2022\u2022\u2022\u2022 " + key.takeLast(4)

    companion object {
        private const val FILE_NAME = "agent_secure_prefs"
        private const val KEY_RING_PREFIX = "api_keys_v2::"
        private const val KEY_ACTIVE_PREFIX = "api_key_active::"
        private const val KEY_CUSTOM_PROVIDERS = "custom_providers_v1"
        /** A key that failed is skipped by rotation for this long (it is still tried when it is the only one). */
        private const val KEY_COOLDOWN_MS = 10 * 60_000L
        const val PROVIDER_TINYFISH = "tinyfish"
        /** Model ids of custom providers: custom/<provider id>/<model id>. */
        const val CUSTOM_MODEL_PREFIX = "custom/"
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
        private const val KEY_MEMORY_NEW_CHATS = "conversation_memory_new_chats"
        private const val KEY_AUTO_TITLES = "auto_titles"
        private const val KEY_BACKUP_PASSPHRASE = "backup_passphrase"
        /** generate_image model: provider id and model id (plain settings, so they are backed up). */
        const val KEY_MEDIA_IMAGE_PROVIDER = "media_image_provider"
        const val KEY_MEDIA_IMAGE_MODEL = "media_image_model"
        /** Same prefix as OllamaCloud.PREFIX. */
        private const val OLLAMA_PREFIX = "ollama/"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_OLLAMA = "ollama"
        /** Where the single key of each built-in provider lived before the key ring. */
        private val LEGACY_KEYS = mapOf(
            PROVIDER_OPENROUTER to KEY_OPENROUTER,
            PROVIDER_OLLAMA to KEY_OLLAMA,
            PROVIDER_TINYFISH to KEY_TINYFISH,
        )
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
