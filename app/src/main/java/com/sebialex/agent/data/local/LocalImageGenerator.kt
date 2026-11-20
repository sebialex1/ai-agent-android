package com.sebialex.agent.data.local

import com.sebialex.agent.domain.tools.GeneratedImage
import com.sebialex.agent.domain.tools.ImageGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

/**
 * generate_image on this phone: the MediaPipe Image Generator runs a Stable-Diffusion-1.5 model
 * on the phone's GPU, nothing leaves the device. The picture always comes back square
 * (512x512-ish): the engine has no aspect-ratio control, so [ImageGenerator.aspectRatio] is
 * honestly ignored. [steps] is read per generation (Settings > On-device > Image: fewer steps
 * finish sooner, the picture gets rougher); the seed is random per request so the same prompt
 * gives varied pictures.
 */
class LocalImageGenerator(
    /** The ready model directory, or null while the model is not on the phone. */
    private val modelDir: () -> File?,
    /** The model's human name, for readable errors. */
    private val modelName: String,
    private val runtime: ImageGenRuntime,
    private val steps: () -> Int = { STEPS },
    private val randomSeed: () -> Int = { ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE) },
    /** Per-diffusion-step readout, (step, total); the app publishes it to the chat's tool row. */
    private val onStep: ((step: Int, total: Int) -> Unit)? = null,
) : ImageGenerator {

    override suspend fun generate(prompt: String, aspectRatio: String?): GeneratedImage = withContext(Dispatchers.IO) {
        val dir = modelDir() ?: throw IOException(
            "The on-device image model ($modelName) is not downloaded yet. " +
                "Get it in Settings > On-device > Image, or pick a cloud image model in Settings > Models. " +
                "Nothing was generated.",
        )
        val count = steps().coerceIn(1, 60)
        val bytes = try {
            runtime.generate(dir, prompt, count, randomSeed()) { step, total, _ -> onStep?.invoke(step, total) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("The on-device image engine failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        GeneratedImage(bytes, "image/png")
    }

    companion object {
        /** Diffusion steps per picture: MediaPipe's default quality/speed middle ground. */
        const val STEPS = 20
    }
}
