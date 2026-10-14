package com.sebialex.agent.data.backup

import com.sebialex.agent.domain.model.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The portable backup: one JSON file with the settings (including custom providers, selected
 * models and per-model reasoning), every conversation, and optionally the API keys.
 *
 * API keys are never written in the clear: they sit in [Envelope.secrets], encrypted with a key
 * derived from the user's passphrase (PBKDF2-HMAC-SHA256 + AES-256-GCM). Without a passphrase the
 * file simply has no keys. The rest of the file is plain JSON so it can be restored without one.
 *
 * Everything is tolerant: unknown fields are ignored, missing fields take defaults, a newer
 * [Envelope.version] is read as far as it is understood, and one broken conversation never blocks
 * the others.
 */
object BackupBundle {
    const val FORMAT = "agent-backup"
    const val VERSION = 1
    const val FILE_NAME = "agent-backup.json"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    @Serializable
    data class Envelope(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val createdAt: Long = 0,
        /** Preference name to value (boolean, number or string); secrets are never in here. */
        val settings: JsonObject = JsonObject(emptyMap()),
        /** Each element is one conversation (a Session), decoded one by one. */
        val conversations: JsonArray = JsonArray(emptyList()),
        /** Encrypted [Secrets], or null when the backup holds no keys. */
        val secrets: EncryptedBlob? = null,
    )

    @Serializable
    data class EncryptedBlob(
        val kdf: String = KDF,
        val iterations: Int = ITERATIONS,
        val salt: String = "",
        val iv: String = "",
        val data: String = "",
    )

    @Serializable
    data class SecretKey(val key: String, val label: String = "", val addedAt: Long = 0, val active: Boolean = false)

    /** Key ring contents by provider id (built-in ones and `custom:<id>`). */
    @Serializable
    data class Secrets(val keys: Map<String, List<SecretKey>> = emptyMap())

    /** What a restore found. */
    data class Parsed(
        val version: Int,
        val createdAt: Long,
        val settings: Map<String, JsonPrimitive>,
        val conversations: List<Session>,
        val skippedConversations: Int,
        val hasSecrets: Boolean,
        val secrets: EncryptedBlob?,
    )

    class BadBackup(message: String) : Exception(message)
    class WrongPassphrase : Exception("Wrong passphrase")

    // ---- Which preference names are secrets ----------------------------------------------------

    private val SECRET_PREFIXES = listOf("api_keys_v2::", "api_key_active::", "backup_passphrase")
    private val SECRET_NAMES = setOf("tinyfish_api_key", "openrouter_api_key", "ollama_api_key")

    /** True for preferences that hold API keys or the backup passphrase: kept out of the plain part. */
    fun isSecretPref(name: String) = name in SECRET_NAMES || SECRET_PREFIXES.any { name.startsWith(it) }

    // ---- Build / parse ----------------------------------------------------------------------------

    fun build(
        settings: Map<String, Any?>,
        sessions: List<Session>,
        secrets: Secrets?,
        passphrase: CharArray?,
        now: Long = System.currentTimeMillis(),
        random: SecureRandom = SecureRandom(),
    ): String {
        val plain = settings.filterKeys { !isSecretPref(it) }.mapNotNull { (k, v) ->
            when (v) {
                is Boolean -> k to JsonPrimitive(v)
                is Int -> k to JsonPrimitive(v)
                is Long -> k to JsonPrimitive(v)
                is String -> k to JsonPrimitive(v)
                else -> null
            }
        }.toMap()
        val blob = if (secrets != null && secrets.keys.values.any { it.isNotEmpty() } && passphrase != null && passphrase.isNotEmpty()) {
            encrypt(json.encodeToString(Secrets.serializer(), secrets).toByteArray(), passphrase, random)
        } else null
        return json.encodeToString(
            Envelope.serializer(),
            Envelope(
                createdAt = now,
                settings = JsonObject(plain),
                conversations = JsonArray(sessions.map { json.encodeToJsonElement(Session.serializer(), it) }),
                secrets = blob,
            ),
        )
    }

    fun parse(text: String): Parsed {
        val env = try {
            val root = json.parseToJsonElement(text) as JsonObject
            if ((root["format"] as? JsonPrimitive)?.content != FORMAT) throw BadBackup("Not a backup file")
            json.decodeFromJsonElement(Envelope.serializer(), root)
        } catch (e: BadBackup) {
            throw e
        } catch (e: Exception) {
            throw BadBackup("Not a backup file")
        }
        if (env.format != FORMAT) throw BadBackup("Not a backup file")
        var skipped = 0
        val sessions = env.conversations.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(Session.serializer(), el) }.getOrNull().also { if (it == null) skipped++ }
        }.filter { it.id.isNotBlank() && !it.id.contains('/') && !it.id.contains("..") }
        val settings = env.settings.entries
            .filter { (_, v) -> v is JsonPrimitive }
            .associate { (k, v) -> k to (v as JsonPrimitive) }
            .filterKeys { !isSecretPref(it) }
        return Parsed(env.version, env.createdAt, settings, sessions, skipped, env.secrets != null, env.secrets)
    }

    /** Decrypts the key ring of a parsed backup; throws [WrongPassphrase] if the passphrase does not fit. */
    fun openSecrets(parsed: Parsed, passphrase: CharArray): Secrets {
        val blob = parsed.secrets ?: return Secrets()
        val bytes = decrypt(blob, passphrase)
        return runCatching { json.decodeFromString(Secrets.serializer(), String(bytes)) }.getOrElse { throw WrongPassphrase() }
    }

    /** Applies a JSON primitive to a typed preference: booleans, ints and strings keep their type. */
    fun primitiveValue(p: JsonPrimitive): Any = p.booleanOrNull ?: (if (p.isString) p.content else p.intOrNull ?: p.content)

    // ---- Merging conversations ------------------------------------------------------------------

    /** Conversations to write: new ones, and ones newer than the local copy. Re-running changes nothing. */
    fun conversationsToImport(incoming: List<Session>, localUpdatedAt: Map<String, Long>): List<Session> =
        incoming.filter { s -> (localUpdatedAt[s.id] ?: Long.MIN_VALUE) < s.updatedAt }

    // ---- Passphrase encryption --------------------------------------------------------------------

    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 200_000
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: ByteArray, passphrase: CharArray, random: SecureRandom = SecureRandom()): EncryptedBlob {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        return EncryptedBlob(KDF, ITERATIONS, b64.encodeToString(salt), b64.encodeToString(iv), b64.encodeToString(cipher.doFinal(plain)))
    }

    fun decrypt(blob: EncryptedBlob, passphrase: CharArray): ByteArray = try {
        if (blob.kdf != KDF || blob.iterations !in 1..5_000_000) throw BadBackup("Unsupported encryption")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, unb64.decode(blob.salt), blob.iterations), GCMParameterSpec(128, unb64.decode(blob.iv)))
        cipher.doFinal(unb64.decode(blob.data))
    } catch (e: BadBackup) {
        throw e
    } catch (e: java.security.GeneralSecurityException) {
        throw WrongPassphrase()
    } catch (e: IllegalArgumentException) {
        throw BadBackup("Damaged backup")
    }
}
