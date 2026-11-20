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
     */
    fun generate(modelDir: File, prompt: String, steps: Int, seed: Int): ByteArray

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

    override fun generate(modelDir: File, prompt: String, steps: Int, seed: Int): ByteArray = synchronized(lock) {
        // A load of ~2 GB of weights on a phone without enough free memory ends in a native
        // abort that cannot be caught: refuse readable first, from [MemoryInfo].
        lowMemory()?.let { throw IOException(it) }
        try {
            val generator = loadedOrOpen(modelDir)
            val result = generator.generate(prompt, steps, seed)
            val bitmap = com.google.mediapipe.framework.image.BitmapExtractor.extract(result.generatedImage())
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            bitmap.recycle()
            bytes
        } catch (e: IOException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IOException("Not enough memory for the image model. Close other apps and try again.", e)
        } catch (e: com.google.mediapipe.framework.MediaPipeException) {
            throw IOException(e.message ?: "the image engine failed to initialize", e)
        }
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
    }
}
