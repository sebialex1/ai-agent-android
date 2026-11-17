package com.sebialex.agent.data.llm

import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.data.web.cancellableIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A generated video: its bytes and MIME type (video/mp4). */
class GeneratedVideo(val bytes: ByteArray, val mime: String)

/** Text-to-video backend for the generate_video tool. */
fun interface VideoGenerator {
    suspend fun generate(prompt: String, aspectRatio: String?, seconds: Int?, resolution: String?): GeneratedVideo
}

/**
 * The parts of the Gemini Interactions video protocol that are pure, so they can be tested
 * without a network: finding the video part of a response, the file id inside its URI, and the
 * state of a file being processed.
 *
 * The SDK's `output_video` convenience field does not exist over REST — the video is inside
 * `steps[].content[]`, as base64 `data` or as a `uri` when delivery is "uri".
 */
object GeminiVideoProtocol {
    const val MODEL = "gemini-omni-1.2-flash"
    const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    const val FILES = "https://generativelanguage.googleapis.com/v1beta/files"

    /** The video a response carries: inline [data] (base64) or a [uri] to poll and download. */
    data class VideoPart(val mime: String, val data: String?, val uri: String?)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun str(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** The first video part of an Interactions response, or null when it carries none. */
    fun videoPart(body: String): VideoPart? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val steps = root["steps"] as? JsonArray ?: return null
        for (step in steps) {
            val content = (step as? JsonObject)?.get("content") as? JsonArray ?: continue
            for (part in content) {
                val o = part as? JsonObject ?: continue
                if (str(o, "type") != "video") continue
                val data = str(o, "data")
                val uri = str(o, "uri")
                if (data == null && uri == null) continue
                return VideoPart(str(o, "mime_type") ?: "video/mp4", data, uri)
            }
        }
        return null
    }

    /** The file id inside a `…/files/<id>…` URI, or null when it is not one. */
    fun fileId(uri: String): String? =
        Regex("files/([A-Za-z0-9._-]+)").find(uri)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    /** The processing state of a file ("ACTIVE", "FAILED", "PROCESSING"), or null when unstated. */
    fun fileState(body: String): String? =
        runCatching { str(json.parseToJsonElement(body).jsonObject, "state") }.getOrNull()

    /** A provider error message, when the body is one. */
    fun error(body: String): String? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        str(o["error"] as? JsonObject ?: return@runCatching null, "message")
    }.getOrNull()

    /** The request body for one generation. Only supported fields are sent. */
    fun requestBody(prompt: String, aspectRatio: String?, seconds: Int?, resolution: String?): String {
        val format = kotlinx.serialization.json.buildJsonObject {
            put("type", "video")
            // URI delivery: a video with audio is usually over the 4 MB inline limit.
            put("delivery", "uri")
            aspectRatio?.takeIf { it in setOf("16:9", "9:16") }?.let { put("aspect_ratio", it) }
            resolution?.takeIf { it in setOf("360p", "720p", "1080p", "4k") }?.let { put("resolution", it) }
            seconds?.takeIf { it in 3..15 }?.let { put("duration", "${it}s") }
        }
        return kotlinx.serialization.json.buildJsonObject {
            put("model", MODEL)
            put("input", prompt)
            put("response_format", format)
        }.toString()
    }
}

/**
 * Video generation through the Gemini Interactions API (Gemini Omni Flash). One request creates
 * the video, its file is polled until ACTIVE, then the bytes are downloaded. Nothing is simulated:
 * a failure is reported with the provider's own message.
 */
class GeminiOmniVideo(
    private val apiKey: () -> String?,
    private val model: String = GeminiVideoProtocol.MODEL,
) : VideoGenerator {

    override suspend fun generate(prompt: String, aspectRatio: String?, seconds: Int?, resolution: String?): GeneratedVideo =
        withContext(Dispatchers.IO) {
            val key = apiKey()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Add a Google Gemini API key in Settings > Models to generate video.")
            val body = GeminiVideoProtocol.requestBody(prompt, aspectRatio, seconds, resolution)
            val created = post("${GeminiVideoProtocol.ENDPOINT}?key=$key", body)
            GeminiVideoProtocol.error(created)?.let { throw IOException(it) }
            val part = GeminiVideoProtocol.videoPart(created)
                ?: throw IOException("the model returned no video" + stateOf(created)?.let { " ($it)" }.orEmpty())
            part.data?.let { return@withContext GeneratedVideo(java.util.Base64.getDecoder().decode(it), part.mime) }
            val uri = part.uri ?: throw IOException("the model returned no video data")
            val id = GeminiVideoProtocol.fileId(uri) ?: throw IOException("unrecognised video URI: $uri")
            awaitActive(id, key)
            GeneratedVideo(download("${GeminiVideoProtocol.FILES}/$id:download?alt=media&key=$key"), part.mime)
        }

    /** Polls the file until it is ACTIVE; FAILED or the time budget ends the call with a reason. */
    private suspend fun awaitActive(id: String, key: String) {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val body = get("${GeminiVideoProtocol.FILES}/$id?key=$key")
            when (GeminiVideoProtocol.fileState(body)) {
                "ACTIVE" -> return
                "FAILED" -> throw IOException("the video generation failed")
            }
            delay(POLL_MS)
        }
        throw IOException("the video was not ready within ${MAX_WAIT_MS / 60_000} minutes")
    }

    private fun stateOf(body: String): String? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["status"] as? JsonPrimitive }
            .getOrNull()?.takeIf { it.isString }?.content

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return cancellableIo(close = { conn.disconnect() }) {
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                read(conn)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return cancellableIo(close = { conn.disconnect() }) {
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                read(conn)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IOException(GeminiVideoProtocol.error(text) ?: "HTTP $code while generating video")
        }
        return text
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        return cancellableIo(close = { conn.disconnect() }) {
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code while downloading the video")
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("the video is over ${MAX_BYTES / (1 shl 20)} MB")
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val MAX_WAIT_MS = 10 * 60_000L
        const val READ_TIMEOUT_MS = 300_000
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}

/**
 * generate_video's backend: the Google Gemini provider the user added, with its own key. No
 * silent fallback — without a Gemini provider and key the tool fails with a readable reason.
 */
class RoutedVideoGenerator(private val settings: SecureSettings) : VideoGenerator {
    override suspend fun generate(prompt: String, aspectRatio: String?, seconds: Int?, resolution: String?): GeneratedVideo {
        val provider = settings.customProviders().firstOrNull {
            com.sebialex.agent.data.llm.ProviderMetadata.isGeminiHost(it.baseUrl)
        } ?: throw IOException(
            "Video generation needs a Google Gemini provider. Add one in Settings > Models > Add provider (Google Gemini) with an API key.",
        )
        val ring = CustomProviders.keyRing(provider.id)
        return GeminiOmniVideo(apiKey = { settings.activeKey(ring) }).generate(prompt, aspectRatio, seconds, resolution)
    }
}