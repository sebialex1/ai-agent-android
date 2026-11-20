package com.sebialex.agent.data.local

import java.io.ByteArrayOutputStream
import java.io.File

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
        val generator = loadedOrOpen(modelDir)
        val result = generator.generate(prompt, steps, seed)
        val bitmap = com.google.mediapipe.framework.image.BitmapExtractor.extract(result.generatedImage())
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        bytes
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
}
