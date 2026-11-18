package com.sebialex.agent.data.secure

import com.sebialex.agent.data.backup.BackupBundle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `.agt` file: a Gemini session exported from a desktop browser, sealed with a passphrase.
 *
 * Login cannot happen inside the app — Google refuses sign-in from embedded WebViews — so the
 * desktop tool does it in a real browser and exports the whole `.google.com` cookie jar here.
 * The envelope is the app's own backup crypto (PBKDF2-HMAC-SHA256 + AES-256-GCM), so the same
 * tested code opens it.
 *
 * The app stores and forwards these cookies; it does not speak Gemini's web protocol itself.
 * That protocol needs Chrome TLS impersonation, which Android's HTTP stack cannot do, so a
 * local shim consumes the jar and the app talks OpenAI to it.
 */
object AgtFile {
    const val FORMAT = "agent-gemini"
    const val VERSION = 1

    /** The cookie the session cannot work without. */
    const val REQUIRED = "__Secure-1PSID"

    @Serializable
    data class Cookie(val name: String, val value: String, val domain: String = ".google.com", val path: String = "/")

    @Serializable
    data class Payload(val authuser: Int = 0, val cookies: List<Cookie> = emptyList())

    @Serializable
    private data class Envelope(
        val format: String = "",
        val version: Int = 0,
        val createdAt: Long = 0,
        val authuser: Int = 0,
        val blob: BackupBundle.EncryptedBlob? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Opens an `.agt` with [passphrase]. Throws [BackupBundle.WrongPassphrase] when it does not
     * fit, [BackupBundle.BadBackup] when the file is not one or holds no usable session.
     */
    fun parse(text: String, passphrase: CharArray): Payload {
        val env = runCatching { json.decodeFromString<Envelope>(text) }.getOrNull()
            ?: throw BackupBundle.BadBackup("Not an .agt file")
        if (env.format != FORMAT) throw BackupBundle.BadBackup("Not an .agt file")
        if (env.version > VERSION) throw BackupBundle.BadBackup("This file was made by a newer version")
        val blob = env.blob ?: throw BackupBundle.BadBackup("The file holds no session")
        val payload = runCatching {
            json.decodeFromString<Payload>(String(BackupBundle.decrypt(blob, passphrase), Charsets.UTF_8))
        }.getOrElse {
            when (it) {
                is BackupBundle.WrongPassphrase -> throw it
                is BackupBundle.BadBackup -> throw it
                else -> throw BackupBundle.BadBackup("The session could not be read")
            }
        }
        if (payload.cookies.none { it.name == REQUIRED && it.value.isNotBlank() }) {
            throw BackupBundle.BadBackup("The session has no $REQUIRED cookie")
        }
        return payload
    }

    /** The jar as the local shim reads it: a name/value map plus the account index. */
    fun shimFile(payload: Payload): String = json.encodeToString(
        ShimPayload.serializer(),
        ShimPayload(payload.authuser, payload.cookies.associate { it.name to it.value }),
    )

    @Serializable
    data class ShimPayload(val authuser: Int, val cookies: Map<String, String>)

    /** Cookie names only, for a status line. A session cookie is a password; never show its value. */
    fun describe(payload: Payload): String =
        "u/${payload.authuser} · ${payload.cookies.size} cookies"
}