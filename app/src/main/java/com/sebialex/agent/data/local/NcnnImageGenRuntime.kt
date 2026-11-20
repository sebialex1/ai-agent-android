package com.sebialex.agent.data.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import android.graphics.Bitmap

/**
 * The second on-device image engine: **ncnn** (Tencent, BSD-3), adapted from EdVince's
 * Stable-Diffusion-NCNN. This is the fallback engine for phones whose GPU drivers cannot run
 * the MediaPipe engine (Google deprecated that task and will not fix hardware-specific GPU
 * failures). It runs the Stable-Diffusion-v1.5 pipeline on the **GPU when the phone exposes a
 * Vulkan compute device** (ncnn speaks Vulkan compute \u2014 a different driver path from the
 * OpenCL one MediaPipe fails on), and on the CPU with fp16-packed arithmetic otherwise;
 * the GPU attempt is bounded to [GPU_INIT_TIMEOUT_MS] inside the engine (some drivers hang
 * forever in setup) and a failure there is memoized ([NcnnGpuMemo]) so later generations
 * start on the CPU straight away. Resolution is 256x256; a picture takes roughly 30-90 s on CPU on a recent
 * flagship, faster where the GPU path works.
 *
 * Model directory layout (see the ncnn entries in [com.sebialex.agent.data.local.ImageModels]):
 * `unet.param` + `unet.bin`, `clip.param` + `clip.bin` (with `vocab.txt`), `vae.param` +
 * `vae.bin`, `log_sigmas.bin`. Loading holds about 2 GB in RAM; the generated picture comes
 * back as PNG bytes, like the MediaPipe engine's.
 */
class NcnnImageGenRuntime(private val context: android.content.Context) : ImageGenRuntime {

    private val lock = Any()
    private var loaded: String? = null

    override fun generate(
        modelDir: File,
        prompt: String,
        steps: Int,
        seed: Int,
        onStep: (step: Int, total: Int, preview: ByteArray?) -> Unit,
    ): ByteArray = synchronized(lock) {
        // A readable refusal when free RAM is too small for the ~2 GB of weights, else null.
        ImageGenMemory.check(context)?.let { throw IOException(it) }
        try {
            generated(modelDir, prompt, steps, seed, onStep)
        } catch (e: IOException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory for the image model. Close other apps and try again.", e)
        } catch (e: UnsatisfiedLinkError) {
            throw IOException("The ncnn image engine is missing on this phone (arm64 only).", e)
        } catch (e: Throwable) {
            throw IOException(e.message ?: "the image engine failed", e)
        }
    }

    private fun generated(
        modelDir: File,
        prompt: String,
        steps: Int,
        seed: Int,
        onStep: (step: Int, total: Int, preview: ByteArray?) -> Unit,
    ): ByteArray {
        val count = steps.coerceIn(1, 60)
        // While the engine starts (Vulkan setup with its bounded GPU attempt, then loading the
        // ~2 GB of weights — up to a couple of minutes and the slowest part of the whole run)
        // nothing comes out of the engine and the chat looked stuck on "starting the engine"
        // until the client's watchdog declared death after 4 quiet minutes. So the heartbeat
        // starts HERE, covering init too: a step-0 tick every 15 s keeps the readout honest and
        // restarts the runtime's silence clock. It stops before sampling begins (so it never
        // overrides a real step), and after 8 minutes it gives up anyway so a hung init still
        // trips the watchdog instead of burning forever.
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        val ticker = Thread {
            var waited = 0
            while (alive.get() && waited < HEARTBEAT_BUDGET_MS) {
                runCatching { Thread.sleep(15_000); waited += 15_000 }
                if (alive.get() && waited < HEARTBEAT_BUDGET_MS) runCatching { onStep(0, count, null) }
            }
        }.apply { isDaemon = true; start() }
        val stopTicker = {
            alive.set(false)
            runCatching { ticker.join(2_000) }
        }
        val rgba = try {
            // One model at a time in RAM (about 2 GB): the weights stay loaded between
            // generations (the service's idle unload closes them), so re-check before paying
            // the load again.
            val path = modelDir.absolutePath
            if (!SdNcnn.isLoadedFor(path)) {
                when (SdNcnn.init(path, !NcnnGpuMemo.broken(context), GPU_INIT_TIMEOUT_MS)) {
                    // The engine timed the Vulkan attempt out itself (it can hang in the
                    // driver) and fell back to the CPU; remember it so this process and later
                    // generations skip the wasted GPU chase for 48 h.
                    SdNcnn.INIT_GPU_TIMED_OUT -> NcnnGpuMemo.mark(context)
                    SdNcnn.INIT_FAILED -> throw IOException("The model directory is missing or broken (unet/clip/vae param+bin, log_sigmas.bin, vocab.txt). Redownload it in Settings > On-device > Image.")
                }
            }
            loaded = path
            android.util.Log.i("sdncnn", "generating on the " + (if (SdNcnn.onGpu()) "GPU (Vulkan)" else "CPU"))
            stopTicker()
            // The JNI callback's onStep returns true = continue; there is no cancel yet, like
            // MediaPipe. ncnn reports every finished step — with no preview (the VAE decode
            // needed for a half-drawn picture would slow every step down), so the chat shows the
            // counter only.
            SdNcnn.txt2img(prompt, DEFAULT_NEGATIVE, count, seed) { done -> onStep(done, count, null); true }
        } finally {
            stopTicker()
        }
        if (rgba == null) throw IOException("the image engine returned no image")
        if (rgba.size != NCNN_IMAGE_WIDTH * NCNN_IMAGE_WIDTH * 4) {
            throw IOException("the image engine returned a broken picture (${rgba.size} bytes)")
        }
        val bitmap = Bitmap.createBitmap(NCNN_IMAGE_WIDTH, NCNN_IMAGE_WIDTH, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
        // PNG out, like [MediaPipeImageGenRuntime], so nothing above the seam needs Android.
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }

    override fun close() = synchronized(lock) {
        if (loaded != null) runCatching { SdNcnn.close() }
        loaded = null
    }

    private companion object {
        /** The sampler always makes 256x256: 32x32 latent x 8. */
        const val NCNN_IMAGE_WIDTH = 256

        /** How long the engine may chase the GPU's driver path before forcing the CPU fallback. */
        const val GPU_INIT_TIMEOUT_MS = 90 * 1000

        /** Heartbeats keep a slow-but-moving init alive; past this the watchdog decides. */
        const val HEARTBEAT_BUDGET_MS = 8 * 60_000

        /** Same default negative prompt the ncnn sample uses, without the anime-only extras. */
        const val DEFAULT_NEGATIVE =
            "deformed, blurry, bad anatomy, disfigured, poorly drawn face, mutation, mutated, " +
                "extra limb, ugly, poorly drawn hands, missing limb, floating limbs, " +
                "disconnected limbs, malformed hands, blur, out of focus, long neck, lowres, " +
                "bad hands, text"
    }
}

/**
 * The JNI surface of the vendored `libsdncnn.so` (app/src/main/native/sdncnn_jni.cpp). One
 * process loads one model at a time; init() frees the previous weights itself.
 */
object SdNcnn {

    /** init() outcomes, returned as its result code. */
    const val INIT_ON_GPU = 0
    const val INIT_ON_CPU = 1
    const val INIT_GPU_TIMED_OUT = 2
    const val INIT_FAILED = 3

    init {
        System.loadLibrary("sdncnn")
    }

    /**
     * Loads CLIP + UNet + VAE from the model directory (init() frees the previous weights
     * itself). With [preferGpu] the Vulkan path is tried first, but only chased for at most
     * [gpuTimeoutMs] — the driver's setup can hang; one of the `INIT_*` codes, always (a failed
     * or timed-out GPU attempt falls back to the CPU inside init).
     */
    external fun init(modelDir: String, preferGpu: Boolean, gpuTimeoutMs: Int): Int

    /** True only when this exact directory's weights are in memory. */
    external fun isLoadedFor(modelDir: String): Boolean

    /** True while the loaded weights run on the GPU (Vulkan); false on the CPU fallback. */
    external fun onGpu(): Boolean

    /**
     * Runs txt2img. [onStep] is called with the number of finished diffusion steps (1-based,
     * not for the final step) and returns false to abandon — this engine could honor Stop,
     * nothing does today. Returns the picture as RGBA bytes (256x256x4), null when abandoned.
     */
    external fun txt2img(positive: String, negative: String, steps: Int, seed: Int, onStep: (Int) -> Boolean): ByteArray?

    external fun close()
}

/**
 * Memo (plain marker file in the shared cache dir, written by either the `:imagegen` process or
 * the app): the ncnn engine's first Vulkan attempt failed or died on this phone. Later
 * generations skip the GPU chase and load straight on the CPU — the retry then costs one quiet
 * process restart, not another 90 s timeout. The memo expires after 48 h so a driver fix (or a
 * cleared cache) restores the GPU attempt without touching the code.
 */
internal object NcnnGpuMemo {

    private const val MAX_AGE_MS = 48L * 60 * 60 * 1000

    fun file(context: android.content.Context) =
        java.io.File(context.cacheDir, "ncnn_gpu_skip")

    /** True while the memo stands (and fresh): skip the GPU attempt. */
    fun broken(context: android.content.Context): Boolean =
        broken(file(context), System.currentTimeMillis())

    /** Same, over a plain file with an injected clock ([file]/[broken] pair is JVM-testable). */
    internal fun broken(f: java.io.File, now: Long): Boolean {
        val at = f.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return false
        if (now - at > MAX_AGE_MS) {
            f.delete()
            return false
        }
        return true
    }

    fun mark(context: android.content.Context) {
        runCatching { file(context).writeText(System.currentTimeMillis().toString()) }
    }
}

/** The free-RAM floor shared by both engines: weights + activations + a working phone. */
internal object ImageGenMemory {
    /** The engines' weights are ~2 GB; keep the same floor MediaPipe's runtime used. */
    const val MIN_AVAIL_BYTES = 2_800L * 1024 * 1024

    /** A readable refusal when free RAM is too small to load the weights, else null. */
    fun check(context: android.content.Context, minAvailBytes: Long = MIN_AVAIL_BYTES): String? {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.availMem >= minAvailBytes) return null
        return "Not enough free memory for the image model (needs " +
            "\u2248 ${LocalModels.formatSize(minAvailBytes)} free; the phone has " +
            "${LocalModels.formatSize(info.availMem)}). Close other apps and try again."
    }
}
