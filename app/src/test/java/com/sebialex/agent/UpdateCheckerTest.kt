package com.sebialex.agent

import com.sebialex.agent.data.update.InstalledApp
import com.sebialex.agent.data.update.UpdateChecker
import com.sebialex.agent.data.update.UpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val cert = "2951755b3951833f1332b19caf578b012e2ac0342c713599a60df902a5fb1f4d"

    /** The shape of GitHub's GET /repos/{owner}/{repo}/releases, trimmed to what is read. */
    private val body = """[
      {"tag_name":"v0.1.23","name":"Agent v0.1.23","draft":false,"prerelease":true,"html_url":"https://github.com/sebialex1/ai-agent-android/releases/tag/v0.1.23",
       "body":"versionCode: 23\nSigning certificate SHA-256: $cert\n\n## What's Changed\n* stuff","published_at":"2026-10-15T10:00:00Z",
       "assets":[{"name":"agent-v0.1.23-signed.apk","size":58000000,"browser_download_url":"https://github.com/sebialex1/ai-agent-android/releases/download/v0.1.23/agent-v0.1.23-signed.apk"}]},
      {"tag_name":"v0.1.30","name":"draft","draft":true,"body":"versionCode: 30","assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]},
      {"tag_name":"v0.1.20","name":"Agent v0.1.20","draft":false,"body":"","assets":[{"name":"agent-v0.1.20-debugkey.apk","size":1,"browser_download_url":"https://x/b.apk"}]},
      {"tag_name":"v1.0-beta","name":"no code","draft":false,"body":"notes","assets":[{"name":"c.apk","browser_download_url":"https://x/c.apk"}]},
      {"tag_name":"v0.1.99","name":"no apk","draft":false,"body":"","assets":[]}
    ]"""

    @Test fun parsesReleasesAndSkipsDrafts() {
        val rs = UpdateChecker.parse(body)
        assertEquals(listOf("v0.1.23", "v0.1.20", "v1.0-beta", "v0.1.99"), rs.map { it.tag })
        val r = rs.first()
        assertEquals(23L, r.versionCode)
        assertEquals(cert, r.certSha256)
        assertEquals("agent-v0.1.23-signed.apk", r.apkName)
        assertEquals(20L, rs[1].versionCode) // from the v0.1.<run> tag
        assertNull(rs[2].versionCode)
    }

    @Test fun offersOnlyANewerInstallableBuild() {
        val rs = UpdateChecker.parse(body)
        val older = UpdateChecker.evaluate(rs, InstalledApp(21, "0.1.21", cert))
        assertTrue(older is UpdateResult.Available)
        older as UpdateResult.Available
        assertEquals("v0.1.23", older.release.tag) // v0.1.99 has no APK, the draft is skipped
        assertFalse(older.keyChanged)
        val same = UpdateChecker.evaluate(rs, InstalledApp(23, "0.1.23", cert))
        assertTrue(same is UpdateResult.UpToDate)
        assertTrue(UpdateChecker.evaluate(rs, InstalledApp(40, "0.1.40", cert)) is UpdateResult.UpToDate)
        assertTrue(UpdateChecker.evaluate(emptyList(), InstalledApp(1, "0.1.0", null)) is UpdateResult.UpToDate)
    }

    @Test fun flagsAKeyChange() {
        val r = UpdateChecker.evaluate(UpdateChecker.parse(body), InstalledApp(20, "0.1.20", "1a3feb081f10d6cfd642838bba22e51fd7bb63baa8c46c127cb29765a28a27a5"))
        assertTrue((r as UpdateResult.Available).keyChanged)
        // Unknown on either side: no warning is invented.
        assertFalse((UpdateChecker.evaluate(UpdateChecker.parse(body), InstalledApp(20, "0.1.20", null)) as UpdateResult.Available).keyChanged)
    }

    @Test(expected = java.io.IOException::class) fun notAListIsAnError() {
        UpdateChecker.parse("""{"message":"Not Found"}""")
    }
}
