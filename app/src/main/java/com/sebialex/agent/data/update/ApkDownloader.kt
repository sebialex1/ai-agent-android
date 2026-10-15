package com.sebialex.agent.data.update

import com.sebialex.agent.data.web.cancellableIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fetches one release APK to a file; [UpdateFlow] decides where and checks the result. */
fun interface ApkDownloader {
    /**
     * Writes [url]'s body to [to] (replacing it), reporting `(bytes written, total or -1)`.
     * Throws on any failure; a cancel stops the transfer at once.
     */
    suspend fun download(url: String, to: File, progress: (Long, Long) -> Unit)
}

/** The real download: plain HTTP(S), redirects followed (GitHub sends assets to its CDN). */
class HttpApkDownloader : ApkDownloader {
    override suspend fun download(url: String, to: File, progress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        cancellableIo(close = { conn.disconnect() }) {
            try {
                conn.connectTimeout = UpdateChecker.SOCKET_TIMEOUT_MILLIS
                // A download may take minutes in total; only a stalled transfer is an error.
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "AgentForAndroid-Update")
                conn.setRequestProperty("Accept", "application/octet-stream")
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("the download answered HTTP $code")
                val total = conn.contentLengthLong
                var done = 0L
                progress(0, total)
                conn.inputStream.use { input ->
                    to.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done, total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
