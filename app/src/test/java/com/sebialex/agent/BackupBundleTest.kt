package com.sebialex.agent

import com.sebialex.agent.data.backup.BackupBundle
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupBundleTest {
    private fun session(id: String, updated: Long) = Session(
        id = id, title = "Chat $id",
        turns = listOf(Turn(id = "t1", role = Role.USER, text = "hello $id", createdAt = 1L)),
        createdAt = 1L, updatedAt = updated, pinned = true,
    )

    private val settings = mapOf<String, Any?>(
        "openrouter_model" to "anthropic/claude-x",
        "ask_before_web" to true,
        "local_context_tokens" to 8192,
        "reasoning_enabled::m" to false,
        "custom_providers_v1" to """[{"id":"a","name":"Groq","baseUrl":"https://x/v1"}]""",
        "api_keys_v2::openrouter" to "SECRET-RING",
        "openrouter_api_key" to "SECRET-LEGACY",
        "backup_passphrase" to "pw",
    )
    private val secrets = BackupBundle.Secrets(
        mapOf("openrouter" to listOf(BackupBundle.SecretKey("sk-or-123", "main", 5, true))),
    )

    @Test fun roundTripKeepsSettingsAndConversations() {
        val text = BackupBundle.build(settings, listOf(session("a", 10), session("b", 20)), null, null, now = 99)
        val p = BackupBundle.parse(text)
        assertEquals(BackupBundle.VERSION, p.version)
        assertEquals(99L, p.createdAt)
        assertEquals(listOf("a", "b"), p.conversations.map { it.id })
        assertEquals(session("a", 10), p.conversations[0])
        assertEquals("anthropic/claude-x", BackupBundle.primitiveValue(p.settings.getValue("openrouter_model")))
        assertEquals(true, BackupBundle.primitiveValue(p.settings.getValue("ask_before_web")))
        assertEquals(8192, BackupBundle.primitiveValue(p.settings.getValue("local_context_tokens")))
        assertEquals(false, BackupBundle.primitiveValue(p.settings.getValue("reasoning_enabled::m")))
        assertTrue(p.settings.containsKey("custom_providers_v1"))
    }

    @Test fun secretsNeverAppearInThePlainPart() {
        val text = BackupBundle.build(settings, emptyList(), secrets, "correct horse".toCharArray())
        assertFalse(text.contains("SECRET-RING"))
        assertFalse(text.contains("SECRET-LEGACY"))
        assertFalse(text.contains("sk-or-123"))
        assertFalse(text.contains("\"pw\""))
        val p = BackupBundle.parse(text)
        assertTrue(p.hasSecrets)
        assertFalse(p.settings.keys.any { BackupBundle.isSecretPref(it) })
    }

    @Test fun keysAreOmittedWithoutPassphrase() {
        val text = BackupBundle.build(settings, emptyList(), secrets, null)
        assertFalse(text.contains("sk-or-123"))
        assertFalse(BackupBundle.parse(text).hasSecrets)
    }

    @Test fun secretsDecryptWithRightPassphraseOnly() {
        val p = BackupBundle.parse(BackupBundle.build(settings, emptyList(), secrets, "correct horse".toCharArray()))
        assertEquals(secrets, BackupBundle.openSecrets(p, "correct horse".toCharArray()))
        try { BackupBundle.openSecrets(p, "wrong".toCharArray()); fail() } catch (_: BackupBundle.WrongPassphrase) {}
    }

    @Test fun encryptionIsSaltedPerBackup() {
        val a = BackupBundle.encrypt("x".toByteArray(), "pw".toCharArray())
        val b = BackupBundle.encrypt("x".toByteArray(), "pw".toCharArray())
        assertFalse(a.data == b.data)
    }

    @Test fun toleratesMissingFieldsUnknownFieldsAndNewerVersion() {
        val p = BackupBundle.parse("""{"format":"agent-backup","version":7,"future":{"x":1},"settings":{"ask_before_web":true,"nested":{"a":1}}}""")
        assertEquals(7, p.version)
        assertTrue(p.conversations.isEmpty())
        assertEquals(setOf("ask_before_web"), p.settings.keys)
    }

    @Test fun oneBrokenConversationDoesNotBlockTheRest() {
        val good = BackupBundle.json.encodeToString(Session.serializer(), session("ok", 1))
        val p = BackupBundle.parse("""{"format":"agent-backup","conversations":[{"nonsense":1},$good,{"id":"../evil","title":"t","turns":[],"createdAt":1}]}""")
        assertEquals(listOf("ok"), p.conversations.map { it.id })
        assertEquals(1, p.skippedConversations)
    }

    @Test fun rejectsForeignFiles() {
        for (bad in listOf("not json", "{}", """{"format":"other"}""")) {
            try { BackupBundle.parse(bad); fail(bad) } catch (_: BackupBundle.BadBackup) {}
        }
    }

    @Test fun importIsIdempotentAndKeepsNewerLocalChats() {
        val incoming = listOf(session("a", 10), session("b", 20), session("c", 30))
        val first = BackupBundle.conversationsToImport(incoming, mapOf("a" to 50L, "b" to 20L))
        assertEquals(listOf("c"), first.map { it.id })
        val second = BackupBundle.conversationsToImport(incoming, mapOf("a" to 50L, "b" to 20L, "c" to 30L))
        assertTrue(second.isEmpty())
    }
}
