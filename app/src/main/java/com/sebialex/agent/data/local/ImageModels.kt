package com.sebialex.agent.data.local

/** Which on-device engine a model runs on ([ImageGenRuntime]'s two implementations). */
enum class ImageEngine {
    /** Google's MediaPipe Image Generator, GPU only (deprecated upstream, driver-dependent). */
    MEDIAPIPE,
    /** ncnn on the CPU; no GPU driver involved, so it also works where MediaPipe cannot. */
    NCNN,
}

/** One file of a [ImageModel.parts]-based model: downloaded straight into the model directory. */
data class ImageModelPart(
    /** File name inside the model directory. */
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

/**
 * An image model that runs on this phone, with the MediaPipe Image Generator (pinned `.zip`
 * downloads unpacked by [ImageModelStore]) or with the ncnn CPU engine (multi-file
 * [parts] downloads). Like the text catalog in [LocalModels], catalog entries are pinned to an
 * exact Hugging Face commit so the downloaded file never changes underneath the app.
 *
 * MediaPipe's engine only runs models whose architecture matches Stable Diffusion v1.5 exactly;
 * ncnn's converted-from-HF file set below is SD 1.5 as well. A model is a directory of converted
 * files rather than one file in both cases.
 */
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
     * Stable Diffusion v1.5 converted for the MediaPipe Image Generator: one fp16 `.bin` per
     * weight tensor plus the CLIP tokenizer vocab, in a single `sd15_bins/` folder the zip
     * unpacks to. The VAE encoder is left out (text-to-image does not use it). Pinned to the
     * Hugging Face commit of `cuesetac/sd15-mediapipe`.
     */
    val catalog: List<ImageModel> = listOf(
        ImageModel(
            id = PREFIX + "sd15",
            name = "SD 1.5",
            tagline = "Stable Diffusion 1.5 on the phone: no network, no key, ~30-60 s per picture.",
            slug = "sd15",
            zipName = "sd15-mediapipe.zip",
            url = hf("cuesetac/sd15-mediapipe", "b7022b79ea70ba767582abb5527e4902e60b290c", "sd15-mediapipe.zip"),
            sizeBytes = 2_067_658_596,
            minRamGb = 8,
            ramTiersGb = listOf(8, 12),
            license = "CreativeML OpenRAIL-M",
            pageUrl = "https://huggingface.co/cuesetac/sd15-mediapipe",
        ),
        ImageModel(
            id = PREFIX + "dreamshaper-8",
            name = "DreamShaper 8",
            tagline = "SD 1.5 fine-tune with a strong illustrated/dreamy look; same engine, ~1.9 GB.",
            slug = "dreamshaper-8",
            zipName = "dreamshaper8-mediapipe.zip",
            url = hf("l3utterfly/sd-mediapipe", "d1d3148a949fce8c226f20e733c7061e641c7b45", "dreamshaper8-mediapipe.zip"),
            sizeBytes = 1_910_649_028,
            minRamGb = 8,
            ramTiersGb = listOf(8, 12),
            license = "CreativeML OpenRAIL-M (mixed, see the repo)",
            pageUrl = "https://huggingface.co/l3utterfly/sd-mediapipe",
        ),
        ImageModel(
            id = PREFIX + "sd15-ncnn",
            name = "SD 1.5 (CPU)",
            tagline = "Runs on the CPU, not the GPU: the engine that works on phones whose " +
                "graphics driver cannot run the MediaPipe engine. 256x256, \u224840 s+ per picture.",
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

    /** Resolves any device image model id, including imported zips, without touching the disk. */
    fun resolve(id: String): ImageModel? = when {
        !isDeviceImageModel(id) -> null
        id.startsWith(IMPORTED_PREFIX) -> id.removePrefix(IMPORTED_PREFIX).takeIf { it.isNotBlank() }?.let(::imported)
        else -> catalog.firstOrNull { it.id == id }
    }

    /** Human name for a device image model id, otherwise the id itself. */
    fun displayName(id: String): String = resolve(id)?.name ?: id

    /** MediaModelRef model value for a device image model (provider is [com.sebialex.agent.data.llm.MediaModels.PROVIDER_DEVICE]). */
    fun refModel(image: ImageModel): String = image.id
}
