package com.sebialex.agent

import com.sebialex.agent.data.local.HfImageHit
import com.sebialex.agent.data.local.HfSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hugging Face **image** search: repos are mapped onto the canonical ncnn file set the
 * vendored engine loads, resolution variants are picked correctly, and hits say what is
 * missing instead of offering a download the engine could never run.
 */
class HfImageSearchTest {

    /** The EdVince-style converted set, like `yui0/stable_diffusion` ships it. */
    private val converted = listOf(
        "UNetModel-256-256-MHA-fp16-opt.param" to 75_185L,
        "UNetModel-MHA-fp16.bin" to 1_720_230_904L,
        "FrozenCLIPEmbedder-fp16.param" to 33_252L,
        "FrozenCLIPEmbedder-fp16.bin" to 246_505_928L,
        "AutoencoderKL-256-256-fp16-opt.param" to 17_095L,
        "AutoencoderKL-fp16.bin" to 99_056_092L,
        "log_sigmas.bin" to 4_000L,
        "vocab.txt" to 478_113L,
    )

    @Test
    fun aCompleteConvertedRepoMapsToAllEightCanonicalFiles() {
        val (picked, missing) = HfSearch.ncnnFiles(converted)
        assertTrue("nothing is missing from a complete set", missing.isEmpty())
        assertEquals(
            listOf("unet.param", "unet.bin", "clip.param", "clip.bin", "vae.param", "vae.bin", "log_sigmas.bin", "vocab.txt"),
            picked.map { it.first },
        )
        val byCanonical = picked.associate { it.first to it }
        assertEquals("UNetModel-256-256-MHA-fp16-opt.param", byCanonical["unet.param"]!!.second)
        assertEquals(1_720_230_904L, byCanonical["unet.bin"]!!.third)
    }

    @Test
    fun subfolderPathsCarryThePickedFileSize() {
        val inSubfolder = listOf(
            "sd 1.5/UNetModel-256-256-MHA-fp16-opt.param" to 11L,
            "sd 1.5/UNetModel-MHA-fp16.bin" to 22L,
            "sd 1.5/FrozenCLIPEmbedder-fp16.param" to 33L,
            "sd 1.5/FrozenCLIPEmbedder-fp16.bin" to 44L,
            "sd 1.5/AutoencoderKL-256-256-fp16-opt.param" to 55L,
            "sd 1.5/AutoencoderKL-fp16.bin" to 66L,
            "sd 1.5/log_sigmas.bin" to 77L,
            "sd 1.5/vocab.txt" to 88L,
        )
        val (picked, missing) = HfSearch.ncnnFiles(inSubfolder)
        assertTrue(missing.isEmpty())
        val unetBin = picked.first { it.first == "unet.bin" }
        assertEquals("sd 1.5/UNetModel-MHA-fp16.bin", unetBin.second)
        assertEquals(22L, unetBin.third)
    }

    @Test
    fun plainUnetFilenamesStartTheCanonicalSet() {
        val simple = listOf(
            "unet256.param" to 1_000L,
            "unet256.bin" to 2_000L,
            "frozenclipembedder.param" to 30L,
            "frozenclipembedder.bin" to 40L,
            "autoencoderkl.param" to 5L,
            "autoencoderkl.bin" to 6L,
            "log_sigmas.bin" to 7L,
            "vocab.txt" to 8L,
        )
        val (picked, missing) = HfSearch.ncnnFiles(simple)
        assertTrue("named-after-the-part files count as converted: $missing", missing.isEmpty())
        assertTrue(picked.any { it.first == "unet.param" && it.second == "unet256.param" })
    }

    @Test
    fun plainDiffusersWeightsReportWhatIsMissing() {
        val diffusers = listOf(
            "unet/diffusion_pytorch_model.safetensors" to 1_000_000L,
            "model_index.json" to 100L,
            "vae/diffusion_pytorch_model.safetensors" to 50_000L,
        )
        val (picked, missing) = HfSearch.ncnnFiles(diffusers)
        assertTrue("no canonical file was picked", picked.isEmpty())
        assertTrue("every engine input is listed as missing", missing.size == 8)
    }

    @Test
    fun a512OnlyRepoCountsForTheMissingListNotForABrokenDownload() {
        val only512 = converted.map { (name, size) ->
            name.replace("256-256", "512-512") to size
        }
        val (picked, missing) = HfSearch.ncnnFiles(only512)
        assertTrue("no unusable 512 param is downloaded", picked.none { it.first == "unet.param" || it.first == "vae.param" })
        assertTrue("unet weights are named as missing", missing.contains("unet.param"))
        assertTrue("vae weights are named as missing", missing.contains("vae.param"))
    }

    @Test
    fun aCompatibleHitBuildsAPartsModelTheStoreCanDownload() {
        val (picked, missing) = HfSearch.ncnnFiles(converted)
        val hit = HfImageHit(
            repo = "someone/sd15-ncnn",
            downloads = 12_345L,
            sizeBytes = picked.sumOf { it.third },
            parts = picked.map { com.sebialex.agent.data.local.ImageModelPart(it.first, "https://huggingface.co/someone/sd15-ncnn/resolve/main/${it.second}", it.third) },
            missing = missing,
        )
        assertTrue(hit.compatible)
        val model = hit.toImageModel()
        assertTrue("the id lands in the on-device prefix", model.id.startsWith("local/image/hf/"))
        assertEquals("someone_sd15-ncnn", model.slug)
        assertEquals(com.sebialex.agent.data.local.ImageEngine.NCNN, model.engine)
        assertTrue("the parts sum to the advertised size again", model.parts.sumOf { it.sizeBytes } == model.sizeBytes)
        assertTrue(model.parts.all { it.url.startsWith("https://huggingface.co/someone/sd15-ncnn/resolve/main/") })
    }

    @Test
    fun anIncompleteHitIsMarkedIncompatible() {
        val (picked, missing) = HfSearch.ncnnFiles(converted.dropLast(2)) // no log_sigmas, no vocab
        val hit = HfImageHit(
            repo = "someone/partial",
            sizeBytes = picked.sumOf { it.third },
            parts = picked.map { com.sebialex.agent.data.local.ImageModelPart(it.first, "https://huggingface.co/someone/partial/resolve/main/${it.second}", it.third) },
            missing = missing,
        )
        assertFalse(hit.compatible)
        assertTrue(hit.missing.containsAll(listOf("log_sigmas.bin", "vocab.txt")))
    }
}
