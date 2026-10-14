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
    /**
     * Phone RAM tiers (GB) this model is recommended for. A recommendation, never a limit:
     * a model can sit in several tiers and a phone below the tier only gets a warning.
     * Empty for imported files.
     */
    val ramTiersGb: List<Int> = emptyList(),
    /** Licence of the weights, for the model card line. */
    val license: String = "",
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
            ramTiersGb = listOf(8),
            license = "Apache-2.0",
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
            ramTiersGb = listOf(12),
            license = "Apache-2.0",
            maxContext = 32_000,
            thinking = true,
            pageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
        ),
        LocalModel(
            id = PREFIX + "lfm2.5-1.2b-instruct",
            name = "LFM2.5 1.2B",
            tagline = "GPU build with tool calling. Good for 6 GB phones.",
            fileName = "LFM2.5-1.2B-Instruct_int4_gpu.litertlm",
            url = hf("litert-community/LFM2.5-1.2B-Instruct", "b830c10c762dcc56e013a1ee25bef8bf6fa0dc1d", "LFM2.5-1.2B-Instruct_int4_gpu.litertlm"),
            sizeBytes = 736_220_768,
            minRamGb = 6,
            maxContext = 4096,
            pageUrl = "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct",
            ramTiersGb = listOf(6),
            license = "LFM Open License v1.0",
        ),
        // ---- Small models (verified, see docs/model-catalogue-verification.md) ----
        small(
            "smollm2-135m-instruct", "SmolLM2 135M", "Ultra-lightweight chat. Basic answers only, no tools; runs on almost any phone.",
            "litert-community/SmolLM2-135M-Instruct", "8111e0a65fda719f0a6855e8e1a8ec8c3f9ccb22", "SmolLM2_135M_Instruct.litertlm",
            142_819_328, tiers = listOf(1, 2), license = "Apache-2.0", maxContext = 2048, tools = false,
        ),
        small(
            "lfm2.5-230m", "LFM2.5 230M", "Tiny and fast, for short answers and simple extraction.",
            "litert-community/LFM2.5-230M", "58f0603b4576964013c87bbd75124a358d48cd44", "LFM2.5-230M_int4.litertlm",
            176_756_720, tiers = listOf(2), license = "LFM Open License v1.0", maxContext = 4096, tools = false,
        ),
        small(
            "qwen3-0.6b", "Qwen3 0.6B", "Smallest Qwen3, with tool calls and an optional thinking mode.",
            "litert-community/Qwen3-0.6B", "e6687177e00897731e7716267eebd4eba0dde7c2", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            344_671_744, tiers = listOf(2, 3), license = "Apache-2.0", maxContext = 4096, thinking = true,
        ),
        small(
            "lfm2.5-350m", "LFM2.5 350M", "Small model made for structured output and tool use.",
            "litert-community/LFM2.5-350M", "0fdead63ee63cd01703adb5f085d0a72b0cb5e20", "LFM2.5-350M_int4.litertlm",
            247_747_696, tiers = listOf(2, 3), license = "LFM Open License v1.0", maxContext = 4096,
        ),
        small(
            "smollm2-360m-instruct", "SmolLM2 360M", "Light general chat. No tool calls.",
            "litert-community/SmolLM2-360M-Instruct", "507c99cfe6541ba2bcd84818786f7b025935e5e1", "SmolLM2_360M_instruct.litertlm",
            373_719_040, tiers = listOf(2, 3), license = "Apache-2.0", maxContext = 2048, tools = false,
        ),
        small(
            "qwen3.5-0.8b", "Qwen3.5 0.8B", "Newer hybrid architecture with thinking and tool calls.",
            "litert-community/Qwen3.5-0.8B", "efb93fca20bc4b65df078b2b0a365d3350b3dcfb", "Qwen3.5-0.8B_int8.litertlm",
            964_861_904, tiers = listOf(4), license = "Apache-2.0", maxContext = 4096, thinking = true,
        ),
        small(
            "qwen3-1.7b", "Qwen3 1.7B", "Best small all-rounder: tools and thinking on a 4 to 7 GB phone.",
            "litert-community/Qwen3-1.7B", "79bf0667b32ae025b972e5fe555fe72ca6e8816c", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            977_184_032, tiers = listOf(4, 5, 6, 7), license = "Apache-2.0", maxContext = 4096, thinking = true,
        ),
        small(
            "smollm2-1.7b-instruct", "SmolLM2 1.7B", "Compact general chat. No tool calls.",
            "litert-community/SmolLM2-1.7B-Instruct", "c0ee765a9f197467ead8f22b9bf7bbeb4e6307c7", "SmolLM2-1_7B-Instruct_dynamic_wi8_afp32.litertlm",
            1_730_949_280, tiers = listOf(4, 5, 6, 7), license = "Apache-2.0", maxContext = 2048, tools = false,
        ),
        small(
            "lfm2.5-2.6b", "LFM2.5 2.6B", "Liquid AI's flagship small model, with thinking and tool calls.",
            "litert-community/LFM2.5-2.6B", "931327b1e272a069a1538a2a51954b304349271b", "LFM2.5-2.6B_int4.litertlm",
            1_668_151_680, tiers = listOf(7, 8), license = "LFM Open License v1.0", maxContext = 4096, thinking = true,
        ),
        small(
            "qwen3-4b", "Qwen3 4B", "Strongest Qwen3 here. Short 2K context: keep chats brief.",
            "litert-community/Qwen3-4B", "8b4c3d3f6e727cdb445f42feff14b808db8da4fa", "qwen3_4b_mixed_int4.litertlm",
            2_659_053_760, tiers = listOf(8), license = "Apache-2.0", maxContext = 2048, thinking = true,
        ),
    )

    /** Builds a pinned, ungated litert-community entry. [tiers] are the recommended RAM tiers. */
    private fun small(
        slug: String, name: String, tagline: String, repo: String, commit: String, file: String, size: Long,
        tiers: List<Int>, license: String, maxContext: Int, thinking: Boolean = false, tools: Boolean = true,
    ) = LocalModel(
        id = PREFIX + slug, name = name, tagline = tagline, fileName = file, url = hf(repo, commit, file),
        sizeBytes = size, minRamGb = tiers.min(), ramTiersGb = tiers, license = license, maxContext = maxContext,
        thinking = thinking, toolCalling = tools, pageUrl = "https://huggingface.co/$repo",
    )

    /** RAM tiers offered in the picker; nothing above 9 GB is added (Gemma 4 E4B keeps its 12 GB). */
    val ramTiers: List<Int> get() = catalog.flatMap { it.ramTiersGb }.distinct().sorted()

    /** Models recommended for [tierGb], in catalogue order. A model may appear in several tiers. */
    fun forTier(tierGb: Int): List<LocalModel> = catalog.filter { tierGb in it.ramTiersGb }

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
