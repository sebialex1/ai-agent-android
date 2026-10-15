package com.sebialex.agent.data.update

import com.sebialex.agent.data.web.cancellableIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** One published build on GitHub Releases. */
data class AppRelease(
    val tag: String,
    val name: String,
    /** The release page (notes and assets). */
    val pageUrl: String,
    /** Direct download of the APK asset; null when the release has none. */
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long?,
    /** From the release body (`versionCode: <n>`) or a `v0.1.<n>` tag; null when neither says. */
    val versionCode: Long?,
    /** SHA-256 of the signing certificate, from the release body; null when not stated. */
    val certSha256: String?,
    val publishedAt: String?,
)

/** What the installed app is. */
data class InstalledApp(val versionCode: Long, val versionName: String, val certSha256: String?)

/** Result of one check. */
sealed interface UpdateResult {
    data class Available(val release: AppRelease, val installed: InstalledApp) : UpdateResult {
        /**
         * The release is signed with another certificate than this install, so Android will
         * refuse it as an update: back up, uninstall, install, restore (once).
         */
        val keyChanged: Boolean get() = release.certSha256 != null && installed.certSha256 != null &&
            !release.certSha256.equals(installed.certSha256, ignoreCase = true)
    }
    data class UpToDate(val latest: AppRelease?, val installed: InstalledApp) : UpdateResult
    /** No connection (DNS or connect failure). A slow or silent GitHub is [TimedOut], not this. */
    data object Offline : UpdateResult
    /** GitHub did not answer within the check's time limit (or a socket timed out). */
    data object TimedOut : UpdateResult {
        const val MESSAGE = "GitHub did not answer in time"
    }
    data class Failed(val message: String) : UpdateResult
}

/**
 * Checks GitHub Releases of the app's repository for a newer build. Read-only: it only reads the
 * release list; downloading and the installer handoff belong to [UpdateFlow].
 *
 * The whole check (connect, response and parsing) runs under one limit, [limitMillis]. HTTP
 * blocks the thread, so a coroutine timeout alone would only take effect after the call returned:
 * the request runs in [cancellableIo], which disconnects the connection the moment the limit is
 * reached, and the check ends as [UpdateResult.TimedOut].
 */
class UpdateChecker(
    private val repo: String = REPO,
    private val api: String = "https://api.github.com",
    private val limitMillis: Long = CHECK_LIMIT_MILLIS,
) {
    suspend fun check(installed: InstalledApp): UpdateResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(limitMillis) {
                val body = get("$api/repos/$repo/releases?per_page=20")
                evaluate(parse(body), installed)
            }
        } catch (e: TimeoutCancellationException) {
            // The limit, not the caller: report it (the caller's own cancel is rethrown below).
            UpdateResult.TimedOut
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            // Connected (or trying to) but no answer in time: GitHub is slow, the phone is not offline.
            UpdateResult.TimedOut
        } catch (e: UnknownHostException) {
            UpdateResult.Offline
        } catch (e: ConnectException) {
            UpdateResult.Offline
        } catch (e: SSLException) {
            UpdateResult.Failed("secure connection to GitHub failed (${e.message ?: "TLS error"})")
        } catch (e: IOException) {
            UpdateResult.Failed(e.message ?: "could not read GitHub Releases")
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return cancellableIo(close = { conn.disconnect() }) { read(conn) }
    }

    private fun read(conn: HttpURLConnection): String {
        try {
            conn.connectTimeout = SOCKET_TIMEOUT_MILLIS
            conn.readTimeout = SOCKET_TIMEOUT_MILLIS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "AgentForAndroid-UpdateCheck")
            val code = conn.responseCode
            if (code !in 200..299) {
                val remaining = conn.getHeaderField("X-RateLimit-Remaining")
                throw IOException(
                    when {
                        (code == 403 || code == 429) && remaining == "0" -> "GitHub's rate limit for unauthenticated checks is used up; try again in an hour"
                        code == 404 -> "the release list was not found ($repo)"
                        else -> "GitHub answered HTTP $code"
                    },
                )
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val REPO = "sebialex1/ai-agent-android"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        /** The limit for one whole check: connect, response and parsing. */
        const val CHECK_LIMIT_MILLIS = 5_000L
        const val SOCKET_TIMEOUT_MILLIS = 5_000

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val CODE_IN_BODY = Regex("(?im)^\\s*versionCode:\\s*(\\d+)\\s*$")
        private val CERT_IN_BODY = Regex("(?im)certificate SHA-256:\\s*([0-9a-f:]{64,95})")
        /** Manual runs are tagged v0.1.<run number>, and the run number is the versionCode. */
        private val RUN_TAG = Regex("^v?0\\.1\\.(\\d+)$")

        /** Releases from the GitHub API answer (drafts skipped). Throws on a body that is not a list. */
        fun parse(body: String): List<AppRelease> {
            val arr = json.parseToJsonElement(body) as? JsonArray ?: throw IOException("unexpected answer from GitHub")
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                val tag = str(o, "tag_name") ?: return@mapNotNull null
                val text = str(o, "body").orEmpty()
                val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { str(it, "name")?.endsWith(".apk", ignoreCase = true) == true }
                    // A build signed with the release key wins over a leftover debug-key asset.
                    .sortedByDescending { if (str(it, "name")?.contains("-signed") == true) 1 else 0 }
                    .firstOrNull()
                AppRelease(
                    tag = tag,
                    name = str(o, "name")?.takeIf { it.isNotBlank() } ?: tag,
                    pageUrl = str(o, "html_url") ?: "$RELEASES_PAGE/tag/$tag",
                    apkUrl = apk?.let { str(it, "browser_download_url") },
                    apkName = apk?.let { str(it, "name") },
                    apkSize = apk?.let { (it["size"] as? JsonPrimitive)?.content?.toLongOrNull() },
                    versionCode = CODE_IN_BODY.find(text)?.groupValues?.get(1)?.toLongOrNull()
                        ?: RUN_TAG.find(tag)?.groupValues?.get(1)?.toLongOrNull(),
                    certSha256 = CERT_IN_BODY.find(text)?.groupValues?.get(1)?.replace(":", "")?.lowercase(),
                    publishedAt = str(o, "published_at"),
                )
            }
        }

        /**
         * The newest installable release (highest versionCode, with an APK) compared with what
         * is installed. Releases whose versionCode cannot be told are never offered: guessing
         * from names could offer a downgrade.
         */
        fun evaluate(releases: List<AppRelease>, installed: InstalledApp): UpdateResult {
            val latest = releases.filter { it.versionCode != null && it.apkUrl != null }.maxByOrNull { it.versionCode!! }
            return if (latest != null && latest.versionCode!! > installed.versionCode) UpdateResult.Available(latest, installed)
            else UpdateResult.UpToDate(latest, installed)
        }

        private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
