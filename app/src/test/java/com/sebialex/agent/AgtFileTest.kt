package com.sebialex.agent

import com.sebialex.agent.data.backup.BackupBundle
import com.sebialex.agent.data.secure.AgtFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The .agt import contract: envelope, passphrase, and what reaches the local shim. */
class AgtFileTest {

    private val pass = "correct horse battery staple".toCharArray()

    private fun payloadJson(authuser: Int = 2, psid: String? = "session-value") = buildString {
        append("""{"authuser":$authuser,"cookies":[""")
        if (psid != null) append("""{"name":"__Secure-1PSID","value":"$psid","domain":".google.com","path":"/"},""")
        append("""{"name":"__Secure-1PSIDTS","value":"rotating-value","domain":".google.com","path":"/"}]}""")
    }

    /** An envelope sealed with the app's own crypto, exactly as the desktop tool writes it. */
    private fun envelope(payload: String = payloadJson(), format: String = AgtFile.FORMAT, version: Int = 1): String {
        val blob = BackupBundle.encrypt(payload.toByteArray(Charsets.UTF_8), pass)
        return """{"format":"$format","version":$version,"createdAt":0,"authuser":2,"blob":{""" +
            """"kdf":"${blob.kdf}","iterations":${blob.iterations},"salt":"${blob.salt}","iv":"${blob.iv}","data":"${blob.data}"}}"""
    }

    @Test fun roundTripsThroughTheAppsOwnCrypto() {
        val p = AgtFile.parse(envelope(), pass)
        assertEquals(2, p.authuser)
        assertEquals(2, p.cookies.size)
        assertEquals("session-value", p.cookies.first { it.name == AgtFile.REQUIRED }.value)
    }

    @Test fun wrongPassphraseIsReportedAsSuch() {
        val e = runCatching { AgtFile.parse(envelope(), "not the passphrase".toCharArray()) }.exceptionOrNull()
        assertTrue(e is BackupBundle.WrongPassphrase)
    }

    @Test fun foreignOrNewerFilesAreRejected() {
        assertTrue(runCatching { AgtFile.parse(envelope(format = "agent-backup"), pass) }.exceptionOrNull() is BackupBundle.BadBackup)
        assertTrue(runCatching { AgtFile.parse(envelope(version = 99), pass) }.exceptionOrNull() is BackupBundle.BadBackup)
        assertTrue(runCatching { AgtFile.parse("not json", pass) }.exceptionOrNull() is BackupBundle.BadBackup)
        assertTrue(runCatching { AgtFile.parse("""{"format":"agent-gemini","version":1}""", pass) }.exceptionOrNull() is BackupBundle.BadBackup)
    }

    @Test fun aSessionWithoutTheRequiredCookieIsNotUsable() {
        val e = runCatching { AgtFile.parse(envelope(payloadJson(psid = null)), pass) }.exceptionOrNull()
        assertTrue(e is BackupBundle.BadBackup)
        assertTrue((e as BackupBundle.BadBackup).message!!.contains(AgtFile.REQUIRED))
    }

    @Test fun shimFileIsTheNameValueMapAndAccount() {
        val p = AgtFile.parse(envelope(), pass)
        val shim = Json.parseToJsonElement(AgtFile.shimFile(p))
        val obj = shim as kotlinx.serialization.json.JsonObject
        assertEquals("2", obj["authuser"].toString())
        val cookies = obj["cookies"] as kotlinx.serialization.json.JsonObject
        assertEquals("session-value", cookies["__Secure-1PSID"].toString().trim('"'))
        assertEquals("rotating-value", cookies["__Secure-1PSIDTS"].toString().trim('"'))
    }

    @Test fun describeNeverLeaksACookieValue() {
        val p = AgtFile.parse(envelope(), pass)
        val text = AgtFile.describe(p)
        assertTrue(text.contains("u/2"))
        assertFalse(text.contains("session-value"))
        assertFalse(text.contains("rotating-value"))
    }
}