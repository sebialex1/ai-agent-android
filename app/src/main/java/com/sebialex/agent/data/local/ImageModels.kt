package com.sebialex.agent.data.local

/**
 * An image model that runs on this phone with the MediaPipe Image Generator (see
 * [ImageGenRuntime]). Like the text catalog in [LocalModels], catalog entries are pinned to an
 * exact Hugging Face commit so the downloaded file never changes underneath the app.
 *
 * The engine only runs models whose architecture matches Stable Diffusion v1.5 exactly, and a
 * model is a directory of converted files rather than one file, so downloads ship as a `.zip`
 * that [ImageModelStore] unpacks (see [ImageZip]).
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
)

object ImageModels {
    /** Every on-device image model id starts with this; never a cloud provider id. */
    const val PREFIX = "local/image/"
    private const val IMPORTED_PREFIX = PREFIX + "file/"

    private fun hf(repo: String, commit: String, file: String) = "https://huggingface.co/$repo/resolve/$commit/$file"

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
