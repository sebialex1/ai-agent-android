package com.sebialex.agent.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** No connection (DNS, connect or timeout failure). */
    data object Offline : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * Checks GitHub Releases of the app's repository for a newer build. Read-only: it never
 * downloads or installs anything; the UI hands the APK link to the system (browser / package
 * installer), where the user confirms the install as usual.
 */
class UpdateChecker(
    private val repo: String = REPO,
    private val api: String = "https://api.github.com",
) {
    suspend fun check(installed: InstalledApp): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val body = get("$api/repos/$repo/releases?per_page=20")
            evaluate(parse(body), installed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            UpdateResult.Offline
        } catch (e: ConnectException) {
            UpdateResult.Offline
        } catch (e: SocketTimeoutException) {
            UpdateResult.Offline
        } catch (e: SSLException) {
            UpdateResult.Failed("secure connection to GitHub failed (${e.message ?: "TLS error"})")
        } catch (e: IOException) {
            UpdateResult.Failed(e.message ?: "could not read GitHub Releases")
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
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
