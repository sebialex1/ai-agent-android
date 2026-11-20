package com.sebialex.agent.data.local

import kotlinx.serialization.Serializable

/** Which on-device engine a model runs on ([ImageGenRuntime]'s two implementations). */
enum class ImageEngine {
    /** Google's MediaPipe Image Generator, GPU only (deprecated upstream, driver-dependent). */
    MEDIAPIPE,
    /** ncnn: Vulkan compute when the phone's GPU allows it, CPU fallback otherwise \u2014
     *  works where MediaPipe cannot (it never touches OpenCL). */
    NCNN,
}

/** One file of a [ImageModel.parts]-based model: downloaded straight into the model directory. */
@Serializable
data class ImageModelPart(
    /** File name inside the model directory. */
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

/**
 * An image model that runs on this phone, with the MediaPipe Image Generator (pinned `.zip`
 * downloads unpacked by [ImageModelStore]) or with the ncnn engine (multi-file
 * [parts] downloads). Like the text catalog in [LocalModels], catalog entries are pinned to an
 * exact Hugging Face commit so the downloaded file never changes underneath the app.
 *
 * MediaPipe's engine only runs models whose architecture matches Stable Diffusion v1.5 exactly;
 * ncnn's converted-from-HF file set below is SD 1.5 as well. A model is a directory of converted
 * files rather than one file in both cases.
 */
@Serializable
data class ImageModel(
    /** Settings model id, always starting with [ImageModels.PREFIX]. */
    val id: String,
    val name: String,
    /** One short line shown under the name. */
    val tagline: String,
    /** Directory name the model unpacks into, unique per model. */
    val slug: String,
    /** Name the download carries until it is unpacked. */
    val zipName: String,
    /** Download URL, null for imported zips. */
    val url: String? = null,
    /** Expected zip size in bytes; 0 when unknown (imported). */
    val sizeBytes: Long = 0,
    /** Phone RAM below which generation will likely be killed by the system. */
    val minRamGb: Int = 0,
    /** Phone RAM tiers (GB) this model is recommended for, like [LocalModel] uses them. */
    val ramTiersGb: List<Int> = emptyList(),
    val license: String = "",
    /** Model card for the user, opened from the On-device tab. */
    val pageUrl: String? = null,
    val imported: Boolean = false,
    /** The engine this model runs on; [ImageEngine.MEDIAPIPE] for the catalog's zip models. */
    val engine: ImageEngine = ImageEngine.MEDIAPIPE,
    /**
     * Individual files downloaded into the model directory (ncnn models); empty for the
     * MediaPipe models, which download one pinned `.zip` instead.
     */
    val parts: List<ImageModelPart> = emptyList(),
)

object ImageModels {
    /** Every on-device image model id starts with this; never a cloud provider id. */
    const val PREFIX = "local/image/"
    private const val IMPORTED_PREFIX = PREFIX + "file/"

    private fun hf(repo: String, commit: String, file: String) = "https://huggingface.co/$repo/resolve/$commit/$file"

    /** Hugging Face commit of the ncnn-converted Stable Diffusion files ([ImageEngine.NCNN]). */
    private const val NCNN_COMMIT = "51e07806e4b3e69f1b455ef5e1f50e0f465ad434"

    /**
     * One catalog model, one engine: ncnn (Vulkan compute where the GPU allows it, CPU
     * otherwise). The MediaPipe Image Generator is deprecated upstream and its GPU-only engine
     * fails outright on several phones' drivers (Xclipse and friends), so the MediaPipe zip
     * entries (`sd15`, `dreamshaper-8`) left the catalog; imported MediaPipe zips still run
     * (the engine is picked by the model directory's shape).
     */
    val catalog: List<ImageModel> = listOf(
        ImageModel(
            id = PREFIX + "sd15-ncnn",
            name = "SD 1.5",
            tagline = "Stable Diffusion 1.5 on the phone: GPU-accelerated (Vulkan) where the " +
                "phone allows it, CPU otherwise. 256x256, \u224840 s+ per picture on CPU.",
            slug = "sd15-ncnn",
            zipName = "sd15-ncnn.zip", // unused: parts download one file at a time
            sizeBytes = 2_066_400_569,
            minRamGb = 8,
            ramTiersGb = listOf(8, 12),
            license = "CreativeML OpenRAIL-M",
            pageUrl = "https://huggingface.co/yui0/stable_diffusion",
            engine = ImageEngine.NCNN,
            parts = listOf(
                ImageModelPart("unet.bin", hf("yui0/stable_diffusion", NCNN_COMMIT, "UNetModel-MHA-fp16.bin"), 1_720_230_904),
                ImageModelPart("unet.param", hf("yui0/stable_diffusion", NCNN_COMMIT, "UNetModel-256-256-MHA-fp16-opt.param"), 75_185),
                ImageModelPart("clip.bin", hf("yui0/stable_diffusion", NCNN_COMMIT, "FrozenCLIPEmbedder-fp16.bin"), 246_505_928),
                ImageModelPart("clip.param", hf("yui0/stable_diffusion", NCNN_COMMIT, "FrozenCLIPEmbedder-fp16.param"), 33_252),
                ImageModelPart("vae.bin", hf("yui0/stable_diffusion", NCNN_COMMIT, "AutoencoderKL-fp16.bin"), 99_056_092),
                ImageModelPart("vae.param", hf("yui0/stable_diffusion", NCNN_COMMIT, "AutoencoderKL-256-256-fp16-opt.param"), 17_095),
                ImageModelPart("log_sigmas.bin", hf("yui0/stable_diffusion", NCNN_COMMIT, "log_sigmas.bin"), 4_000),
                ImageModelPart("vocab.txt", hf("yui0/stable_diffusion", NCNN_COMMIT, "vocab.txt"), 478_113),
            ),
        ),
    )

    /** Catalogue entries, in catalogue order. */
    val all: List<ImageModel> get() = catalog

    /** A user-imported image model zip, identified by its file name (= the directory it unpacks to). */
    fun imported(zipName: String) = ImageModel(
        id = IMPORTED_PREFIX + zipName,
        name = zipName.removeSuffix(".zip"),
        tagline = "Imported zip",
        // The directory of record is the zip name itself, so re-scanning the models folder
        // rebuilds exactly the same id ("sd15" as a zip name is rejected at import time).
        slug = zipName,
        zipName = zipName,
        imported = true,
    )

    fun isDeviceImageModel(id: String): Boolean = id.startsWith(PREFIX)

    /** Resolves any device image model id, including imported zips, without touching the disk.
     *  [extra] carries dynamically added entries (Hugging Face search) beyond the catalog. */
    fun resolve(id: String, extra: List<ImageModel> = emptyList()): ImageModel? = when {
        !isDeviceImageModel(id) -> null
        id.startsWith(IMPORTED_PREFIX) -> id.removePrefix(IMPORTED_PREFIX).takeIf { it.isNotBlank() }?.let(::imported)
        else -> (catalog + extra).firstOrNull { it.id == id }
    }

    /** Human name for a device image model id, otherwise the id itself. */
    fun displayName(id: String, extra: List<ImageModel> = emptyList()): String = resolve(id, extra)?.name ?: id

    /** MediaModelRef model value for a device image model (provider is [com.sebialex.agent.data.llm.MediaModels.PROVIDER_DEVICE]). */
    fun refModel(image: ImageModel): String = image.id
}
