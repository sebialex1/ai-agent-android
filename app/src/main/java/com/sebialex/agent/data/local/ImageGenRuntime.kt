package com.sebialex.agent.data.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * The heavy on-device engine behind [LocalImageGenerator], as a seam: the real engine
 * ([MediaPipeImageGenRuntime]) wraps Google's MediaPipe Image Generator task and touches its
 * Android-only classes (Context, Bitmap, MPImage); unit tests fake this interface.
 *
 * Returns PNG bytes, so nothing above this seam needs Android graphics classes. Generation is
 * synchronous and slow (tens of seconds); MediaPipe has no cancel, so the caller runs it on
 * [kotlinx.coroutines.Dispatchers.IO] and simply lets an abandoning caller's job finish.
 */
interface ImageGenRuntime {
    /**
     * Generates one image for [prompt], completing [steps] diffusion steps with [seed]
     * (deterministic: the same seed and prompt reproduce the same picture). [modelDir] holds the
     * converted model files. Throws on failure (JDK or native); the seam makes no thread promises.
     *
     * [onStep] reports each finished diffusion step while generation runs — as (step, steps) and
     * the step's preview picture (JPEG bytes) when the engine produced one (null when not). It is
     * not called on any particular thread and never after this call returned.
     */
    fun generate(
        modelDir: File,
        prompt: String,
        steps: Int,
        seed: Int,
        onStep: (step: Int, total: Int, preview: ByteArray?) -> Unit = { _, _, _ -> },
    ): ByteArray

    /** Drops the loaded generator (it holds the weights in RAM). Safe to call any time. */
    fun close() {}
}

/**
 * The real engine: Google's MediaPipe Image Generator
 * (`com.google.mediapipe:tasks-vision-image-generator`), GPU accelerated, running one
 * Stable-Diffusion-v1.5-architecture model in memory at a time. Loading a model takes a while,
 * so the last one stays loaded until another directory is generated with.
 */
class MediaPipeImageGenRuntime(private val context: android.content.Context) : ImageGenRuntime {

    private val lock = Any()
    private var loaded: Pair<File, com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator>? = null

    override fun generate(
        modelDir: File,
        prompt: String,
        steps: Int,
        seed: Int,
        onStep: (step: Int, total: Int, preview: ByteArray?) -> Unit,
    ): ByteArray = synchronized(lock) {
        // A load of ~2 GB of weights on a phone without enough free memory ends in a native
        // abort that cannot be caught: refuse readable first, from [MemoryInfo].
        lowMemory()?.let { throw IOException(it) }
        try {
            val final = generated(modelDir, prompt, steps, seed, onStep)
            png(final.generatedImage())
        } catch (e: IOException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory for the image model. Close other apps and try again.", e)
        } catch (e: com.google.mediapipe.framework.MediaPipeException) {
            throw IOException(e.message ?: "the image engine failed to initialize", e)
        }
    }

    /**
     * The step loop: MediaPipe's own one-shot generate() written out — setInputs() then exactly
     * `steps` calls to execute() (its documented contract, verified in the AAR's bytecode:
     * execute() advances a per-inputs step counter and, after the final one, resets it), with
     * the last call returning the final image. Every third step also asks for the half-drawn
     * picture (showResult) — the chat shows it growing. If the iteration API itself fails
     * before the first step finished, a fresh engine falls back to the one-shot generate():
     * the picture still happens, just without the step readout.
     */
    private fun generated(
        modelDir: File,
        prompt: String,
        steps: Int,
        seed: Int,
        onStep: (step: Int, total: Int, preview: ByteArray?) -> Unit,
    ): com.google.mediapipe.tasks.vision.imagegenerator.ImageGeneratorResult {
        var generator = loadedOrOpen(modelDir)
        var stepsDone = 0
        try {
            generator.setInputs(prompt, steps, seed)
            var result: com.google.mediapipe.tasks.vision.imagegenerator.ImageGeneratorResult? = null
            for (step in 1..steps) {
                // MediaPipe's own one-shot generate() runs every step with showResult false, so
                // the last call here — and the step counter it advances — is exactly its last
                // step.
                val isLast = step == steps
                result = generator.execute(!isLast && step % PREVIEW_EVERY == 0)
                stepsDone = step
                if (!isLast) onStep(step, steps, preview(result)) else onStep(step, steps, null)
            }
            return result ?: throw IOException("the image engine returned no image")
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            if (stepsDone == 0 && generator !== loaded?.second) throw e
            if (stepsDone == 0) {
                // The engine state after a failed first step is not known safe: rebuild it.
                runCatching { generator.close() }
                loaded = null
                generator = loadedOrOpen(modelDir)
                return generator.generate(prompt, steps, seed)
            }
            throw e
        }
    }
    private fun preview(result: com.google.mediapipe.tasks.vision.imagegenerator.ImageGeneratorResult?): ByteArray? {
        val image = result?.generatedImage() ?: return null
        return try {
            // Previews are throwaway (the finished picture is encoded separately as PNG), so
            // they go out as JPEG: a noisy half-drawn diffusion frame compresses to PNG slowly,
            // and this runs inside the drawing loop.
            val bitmap = com.google.mediapipe.framework.image.BitmapExtractor.extract(image)
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            bitmap.recycle()
            bytes
        } catch (t: Throwable) {
            null // A preview is a nicety; the generation itself must not fail for it.
        }
    }

    private fun png(image: com.google.mediapipe.framework.image.MPImage): ByteArray {
        val bitmap = com.google.mediapipe.framework.image.BitmapExtractor.extract(image)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }

    /** A readable refusal when free RAM is too small to load the weights, else null. */
    private fun lowMemory(): String? {
        val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.availMem >= MIN_AVAIL_BYTES) return null
        return "Not enough free memory for the image model (needs " +
            "\u2248 ${LocalModels.formatSize(MIN_AVAIL_BYTES)} free; the phone has " +
            "${LocalModels.formatSize(info.availMem)}). Close other apps and try again."
    }

    /** The generator for [modelDir], opening (and freeing) engines when the directory changes. */
    private fun loadedOrOpen(modelDir: File): com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator {
        loaded?.let { (dir, generator) ->
            if (dir == modelDir) return generator
            runCatching { generator.close() }
        }
        val options = com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.ImageGeneratorOptions.builder()
            .setImageGeneratorModelDirectory(modelDir.absolutePath)
            .build()
        val generator = com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.createFromOptions(context, options)
        loaded = modelDir to generator
        return generator
    }

    override fun close() = synchronized(lock) {
        loaded?.let { (_, generator) -> runCatching { generator.close() } }
        loaded = null
    }

    private companion object {
        /** Weights + activations + the OS's idea of a working phone; a floor, not a guarantee. */
        val MIN_AVAIL_BYTES = 2_800L * 1024 * 1024

        /** A half-drawn picture is asked for every Nth step (Google's own sample does every 5th). */
        const val PREVIEW_EVERY = 3
    }
}
