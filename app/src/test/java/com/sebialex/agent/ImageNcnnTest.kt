package com.sebialex.agent

import com.sebialex.agent.data.local.ImageEngine
import com.sebialex.agent.data.local.ImageModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ncnn (CPU) image engine path: catalog integrity for the parts-based model, and the model
 * directory layout its native engine ([com.sebialex.agent.data.local.NcnnImageGenRuntime]
 * loading unet/clip/vae) expects.
 */
class ImageNcnnTest {

    @Test
    fun ncnnCatalogEntryPinsSummingParts() {
        val ncnn = ImageModels.all.filter { it.engine == ImageEngine.NCNN }
        assertTrue("the ncnn model exists in the image catalog", ncnn.isNotEmpty())
        for (m in ncnn) {
            assertTrue("parts are pinned to files", m.parts.isNotEmpty())
            assertTrue(
                "the sum of the parts equals the advertised size for ${m.id}",
                m.parts.sumOf { it.sizeBytes } == m.sizeBytes,
            )
            assertTrue("all parts carry https URLs", m.parts.all { it.url.startsWith("https://") })
            assertTrue("engine models are roomy enough for the weights", m.minRamGb >= 8)
        }
    }

    @Test
    fun ncnnModelLayoutCarriesTheNativeExpectedFiles() {
        val ncnn = ImageModels.all.filter { it.engine == ImageEngine.NCNN }
        for (m in ncnn) {
            val names = m.parts.map { it.name }.toSet()
            // The JNI init() loads clip.param/.bin, unet.param/.bin, vae.param/.bin and reads
            // log_sigmas.bin; the tokenizer reads vocab.txt. ImageGenService picks this engine
            // by unet.param's presence.
            for (f in listOf("unet.param", "unet.bin", "clip.param", "clip.bin", "vae.param", "vae.bin", "log_sigmas.bin", "vocab.txt")) {
                assertTrue("${m.id} is missing the engine file $f", f in names)
            }
        }
    }

    @Test
    fun mediapipeEntriesStayZipBased() {
        val mp = ImageModels.all.filter { it.engine == ImageEngine.MEDIAPIPE }
        assertTrue("the catalog still ships MediaPipe entries", mp.isNotEmpty())
        for (m in mp) {
            assertTrue("zip models have no parts", m.parts.isEmpty())
            assertTrue("zip models carry a zip URL", !m.url.isNullOrBlank())
        }
    }

    @Test
    fun ncnnModelDirectoryLooksReadyToTheStore() {
        // The store's Ready test is ImageZip.looksLikeModelDir: any directory containing weight
        // .bins counts (ncnn dirs hold unet.bin, clip.bin, vae.bin). No bpe vocab needed.
        val dir = File.createTempFile("imagedirt", "tmp").let { it.delete(); it.apply { mkdirs() } }
        try {
            File(dir, "unet.param").writeText("net")
            File(dir, "unet.bin").writeBytes(ByteArray(16))
            com.sebialex.agent.data.local.ImageZip.let { zip ->
                assertTrue(zip.looksLikeModelDir(dir))
                assertEquals(dir, zip.locateModelDir(dir))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
