package com.sebialex.agent.domain.agent

/**
 * Several API keys per provider, one of them active. When a request fails because of the key
 * (invalid, out of credit, rate limited), [rotateKey] marks it as failing for a while and hands
 * out the next one, so the chat keeps working on a second key. Provider ids are plain strings:
 * [OPENROUTER], [OLLAMA], [TINYFISH] or `custom:<id>` for a user-added provider.
 */
interface KeyPool {
    /** The key to use now for [provider], or null when it has none. */
    fun activeKey(provider: String): String?

    /** How many keys [provider] has. */
    fun keyCount(provider: String): Int

    /**
     * Marks [failed] as failing (with [reason]) and makes the next usable key active. Returns
     * that key, or null when every other key of [provider] failed recently too.
     */
    fun rotateKey(provider: String, failed: String, reason: String = ""): String?

    /** A request with [key] went through: it is healthy again. */
    fun markKeyOk(provider: String, key: String) {}

    companion object {
        const val OPENROUTER = "openrouter"
        const val OLLAMA = "ollama"
        const val TINYFISH = "tinyfish"
        const val CUSTOM_PREFIX = "custom:"

        private val KEY_HTTP = Regex("\\bHTTP (401|402|403|429)\\b")
        private val KEY_WORDS = listOf(
            "rate limit", "rate-limit", "ratelimit", "too many requests", "quota", "insufficient", "credit",
            "invalid api key", "invalid_api_key", "incorrect api key", "unauthorized", "unauthenticated",
            "api key not valid", "billing", "payment required",
        )

        /** Whether an error message points at the key (so another key may succeed). */
        fun isKeyFailure(message: String?): Boolean {
            val m = message ?: return false
            if (KEY_HTTP.containsMatchIn(m)) return true
            val lower = m.lowercase()
            return KEY_WORDS.any { it in lower }
        }
    }
}
