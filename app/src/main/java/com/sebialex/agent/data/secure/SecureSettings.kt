package com.sebialex.agent.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
        val model: String,
        val askBeforeWeb: Boolean,
    )

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun tinyFishKey(): String? = prefs.getString(KEY_TINYFISH, null)?.takeIf { it.isNotBlank() }
    fun openRouterKey(): String? = prefs.getString(KEY_OPENROUTER, null)?.takeIf { it.isNotBlank() }
    fun model(): String = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun askBeforeWeb(): Boolean = prefs.getBoolean(KEY_ASK_WEB, false)

    fun setTinyFishKey(value: String?) = edit { putOrRemove(KEY_TINYFISH, value) }
    fun setOpenRouterKey(value: String?) = edit { putOrRemove(KEY_OPENROUTER, value) }
    fun setModel(value: String?) = edit { putOrRemove(KEY_MODEL, value) }
    fun setAskBeforeWeb(value: Boolean) = edit { putBoolean(KEY_ASK_WEB, value) }

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
        model = model(),
        askBeforeWeb = askBeforeWeb(),
    )

    private fun hint(key: String?): String = if (key == null) "" else "\u2022\u2022\u2022\u2022 " + key.takeLast(4)

    companion object {
        private const val FILE_NAME = "agent_secure_prefs"
        private const val MASTER_KEY_ALIAS = "agent_master_key"
        private const val KEY_TINYFISH = "tinyfish_api_key"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_MODEL = "openrouter_model"
        private const val KEY_ASK_WEB = "ask_before_web"
        const val DEFAULT_MODEL = "openrouter/auto"
    }
}
