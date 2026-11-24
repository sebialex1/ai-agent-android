package com.sebialex.agent.data.llm

import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.agent.KeyPool
import com.sebialex.agent.domain.tools.GeneratedImage
import com.sebialex.agent.domain.tools.ImageGenerator
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The image model ids generate_image can use on Pollinations, as checked against the live
 * service. The free tier (the classic `image.pollinations.ai` URL, no sign-up) serves its one
 * anonymous model ([FREE_MODEL]); with a key (enter.pollinations.ai) the bigger catalog of
 * `gen.pollinations.ai` opens. [FREE_MODEL] stays on the free endpoint even when a key is saved.
 */
object PollinationsModels {
    /** The classic endpoint's model: every anonymous request is served by it. */
    const val FREE_MODEL = "flux"

    /** Image models for the picker: the free one first, then keyed catalog entries. */
    fun imageModels(): List<MediaModelOption> = listOf(
        option(FREE_MODEL, "Flux", "Pollinations free tier \u00b7 no key needed"),
        option("tongyi-mai/z-image-turbo", "Z-Image Turbo", "Pollinations API \u00b7 key"),
        option("microsoft/mai-image-2.7-flash", "MAI Image 2.7 Flash", "Pollinations API \u00b7 key"),
        option("black-forest-labs/flux.1.1-pro", "FLUX 1.1 Pro", "Pollinations API \u00b7 key"),
        option("black-forest-labs/flux.2-flex", "FLUX.2 Flex", "Pollinations API \u00b7 key"),
    )

    private fun option(model: String, name: String, source: String) =
        MediaModelOption(MediaModelRef(MediaModels.PROVIDER_POLLINATIONS, model), "Pollinations", name, source)
}

/**
 * generate_image through Pollinations: a public image service that works with no sign-up and no
 * API key. Without a key the request is a plain `GET https://image.pollinations.ai/prompt/<prompt>`
 * (anonymous free tier, rate-limited per address to about one image every 45 s; beyond the limit
 * it answers HTTP 402). With a key saved in Settings (optional, from enter.pollinations.ai) the
 * paid catalog of `gen.pollinations.ai` is used instead, which lifts that limit:
 * `GET /image/<prompt>?model=...` with `Authorization: Bearer`.
 *
 * Both endpoints answer 200 with the image bytes themselves (a JPEG on the free tier) \u2014 not with
 * a JSON envelope \u2014 and errors as JSON `{"error":{"message":...}}`.
 */
class PollinationsImages(
    private val keys: KeyPool,
    /** Chosen in Settings > Models > Image generation; [PollinationsModels.FREE_MODEL] is the free entry. */
    private val model: String,
    /** Overridable so tests can point at a local fake of both endpoints. */
    private val classicRoot: String = "https://image.pollinations.ai",
    private val genRoot: String = "https://gen.pollinations.ai",
    /** New pictures vary when the prompt repeats: a different seed each call. */
    private val seed: () -> Long = { Random.nextLong(0, 1_000_000_000) },
) : ImageGenerator {
    private val ring = SecureSettings.PROVIDER_POLLINATIONS

    override suspend fun generate(prompt: String, aspectRatio: String?): GeneratedImage =
        withContext(Dispatchers.IO) { generateInner(prompt, aspectRatio) }

    private fun generateInner(prompt: String, aspectRatio: String?): GeneratedImage {
        val key = keys.activeKey(ring)
        val (width, height) = sizeFor(aspectRatio)
        // URLEncoder is form-encoding: a path segment must carry %20 for a space, not a +.
        val encoded = URLEncoder.encode(prompt.trim().ifEmpty { "an interesting picture" }, "UTF-8").replace("+", "%20")
        if (key == null || model == PollinationsModels.FREE_MODEL) {
            // Free and keyless: the classic URL, no model param (it serves its one free model).
            return fetch("$classicRoot/prompt/$encoded?width=$width&height=$height&seed=${seed()}", null)
        }
        val url = "$genRoot/image/$encoded?model=" + URLEncoder.encode(model, "UTF-8") +
            "&width=$width&height=$height&seed=${seed()}"
        return fetch(url, "Bearer ${key.trim()}")
    }

    /** One GET; one retry when the service's gateway hiccups. Errors become readable messages. */
    private fun fetch(url: String, authorization: String?): GeneratedImage {
        var retries = 0
        while (true) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 180_000
                if (authorization != null) conn.setRequestProperty("Authorization", authorization)
                val code = conn.responseCode
                if (code in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val mime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
                    return GeneratedImage(bytes, mime.ifEmpty { "image/jpeg" })
                }
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = MediaModels.errorDetail(text)
                if (code in 500..599 && retries++ == 0) {
                    Thread.sleep(2_000)
                    continue
                }
                throw IOException(errorText(code, detail, authorization != null))
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }

    /** The failure message, naming the one thing this side of the protocol knows how to fix. */
    private fun errorText(code: Int, detail: String?, keySaved: Boolean): String {
        val at = if (detail.isNullOrBlank()) "" else ": $detail"
        return when {
            code == 402 || code == 429 ->
                "Pollinations' free tier allows about one image every 45 s per address. Wait a " +
                    "moment and try again, or add a Pollinations key (from enter.pollinations.ai) " +
                    "in Settings > Models > Pollinations to lift the limit."
            code == 401 ->
                if (keySaved)
                    "Pollinations rejected the saved key (HTTP 401). Check it in Settings > Models > " +
                        "Pollinations, or remove it to use the free no-key tier."
                else "Pollinations needs a key (HTTP 401) for this model. Add one in Settings > " +
                    "Models > Pollinations, or pick the free model (Flux) which needs none."
            code == 403 ->
                "Pollinations refused this prompt (HTTP 403): its safety filter declined it.$at"
            else -> "HTTP $code$at"
        }
    }

    companion object {
        /**
         * The pixel size for [aspectRatio] ("w:h"), kept inside the 768 \u00d7 768 box the free
         * endpoint serves and rounded to even numbers. Anything unreadable becomes the square
         * default.
         */
        internal fun sizeFor(aspectRatio: String?): Pair<Int, Int> {
            val (w, h) = aspectRatio?.trim()
                ?.let { r -> Regex("^(\\d{1,2}):(\\d{1,2})$").find(r)?.destructured }
                ?.let { (w, h) -> w.toInt() to h.toInt() }
                ?.takeIf { (w, h) -> w > 0 && h > 0 }
                ?: return 768 to 768
            val even = { v: Int -> (v / 2) * 2 }
            return if (w >= h) even(768) to even(768 * h / w) else even(768 * w / h) to even(768)
        }
    }
}
