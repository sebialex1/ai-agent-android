package com.sebialex.agent.data.local

/**
 * A model that runs on this phone with LiteRT-LM. Catalog entries are pinned to an exact
 * Hugging Face commit so the downloaded file never changes underneath the app; imported files
 * ([imported] = true) come from the user's own storage.
 */
data class LocalModel(
    /** Settings model id, always starting with [LocalModels.PREFIX]. */
    val id: String,
    val name: String,
    /** One short line shown under the name. */
    val tagline: String,
    /** File name inside the models directory. */
    val fileName: String,
    /** Download URL, null for imported files. */
    val url: String? = null,
    /** Expected size in bytes; 0 when unknown (imported). Used to verify a finished download. */
    val sizeBytes: Long = 0,
    /** Phone RAM below which the model is likely to fail to load. */
    val minRamGb: Int = 0,
    /** Largest context the model supports. The engine uses the smaller of this and the setting. */
    val maxContext: Int = 4096,
    /** The model's chat template has a thinking switch (`enable_thinking`). */
    val thinking: Boolean = false,
    /** The model was trained for tool calls, so the agent's tools are offered to it. */
    val toolCalling: Boolean = true,
    /** Shown first and preselected in the picker. */
    val recommended: Boolean = false,
    val imported: Boolean = false,
    /** Model card for the user, opened from the On-device tab. */
    val pageUrl: String? = null,
)

object LocalModels {
    /** Every on-device model id starts with this; OpenRouter ids never do. */
    const val PREFIX = "local/"
    private const val IMPORTED_PREFIX = PREFIX + "file/"

    private fun hf(repo: String, commit: String, file: String) = "https://huggingface.co/$repo/resolve/$commit/$file"

    /**
     * Curated, ungated `.litertlm` builds from the LiteRT community on Hugging Face. The Gemma 4
     * files and commits are the ones Google's AI Edge Gallery ships (with built-in multi-token
     * prediction); sizes are the exact byte counts reported by Hugging Face.
     */
    val catalog: List<LocalModel> = listOf(
        LocalModel(
            id = PREFIX + "gemma-4-e2b-it",
            name = "Gemma 4 E2B",
            tagline = "Fast everyday model with tool use. About 50 tokens/s on a recent flagship GPU.",
            fileName = "gemma-4-E2B-it.litertlm",
            url = hf("litert-community/gemma-4-E2B-it-litert-lm", "7fa1d78473894f7e736a21d920c3aa80f950c0db", "gemma-4-E2B-it.litertlm"),
            sizeBytes = 2_583_085_056,
            minRamGb = 8,
            maxContext = 32_000,
            thinking = true,
            recommended = true,
            pageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
        ),
        LocalModel(
            id = PREFIX + "gemma-4-e4b-it",
            name = "Gemma 4 E4B",
            tagline = "Stronger reasoning and tool use, slower. Best on 12 GB phones.",
            fileName = "gemma-4-E4B-it.litertlm",
            url = hf("litert-community/gemma-4-E4B-it-litert-lm", "9695417f248178c63a9f318c6e0c56cb917cb837", "gemma-4-E4B-it.litertlm"),
            sizeBytes = 3_654_467_584,
            minRamGb = 12,
            maxContext = 32_000,
            thinking = true,
            pageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
        ),
        LocalModel(
            id = PREFIX + "lfm2.5-1.2b-instruct",
            name = "LFM2.5 1.2B",
            tagline = "Smallest and lightest, GPU build with tool calling. Good for 6 GB phones.",
            fileName = "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
            url = hf("litert-community/LFM2.5-1.2B-Instruct", "b830c10c762dcc56e013a1ee25bef8bf6fa0dc1d", "LFM2.5-1.2B-Instruct_int4_gpu.litertlm"),
            sizeBytes = 736_220_768,
            minRamGb = 6,
            maxContext = 4096,
            pageUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct",
        ),
    )

    fun isLocal(model: String): Boolean = model.startsWith(PREFIX)

    fun catalogEntry(id: String): LocalModel? = catalog.firstOrNull { it.id == id }

    /** A user-imported `.litertlm` file, identified by its file name. */
    fun imported(fileName: String) = LocalModel(
        id = IMPORTED_PREFIX + fileName,
        name = fileName.removeSuffix(".litertlm"),
        tagline = "Imported file",
        fileName = fileName,
        maxContext = 32_000,
        // Detected from the file itself when it is loaded (see LocalEngine).
        toolCalling = true,
        imported = true,
    )

    /** Resolves any local id, including imported files, without touching the disk. */
    fun resolve(id: String): LocalModel? = when {
        !isLocal(id) -> null
        id.startsWith(IMPORTED_PREFIX) -> id.removePrefix(IMPORTED_PREFIX).takeIf { it.isNotBlank() }?.let(::imported)
        else -> catalogEntry(id)
    }

    /** Human name for any model id: the local model's name, otherwise the OpenRouter id itself. */
    fun displayName(model: String): String = resolve(model)?.name ?: model

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1e6)
        else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1e3)
    }
}
