package com.sebialex.agent

import com.sebialex.agent.data.local.ImageGenRuntime
import com.sebialex.agent.data.local.ImageModels
import com.sebialex.agent.data.local.ImageZip
import com.sebialex.agent.data.local.LocalImageGenerator
import com.sebialex.agent.data.llm.MediaModelRef
import com.sebialex.agent.data.llm.MediaModels
import com.sebialex.agent.data.llm.RoutedImageGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImageZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, ByteArray>, to: File) {
        ZipOutputStream(FileOutputStream(to)).use { zs ->
            entries.forEach { (name, content) ->
                zs.putNextEntry(ZipEntry(name))
                zs.write(content)
                zs.closeEntry()
            }
        }
    }

    @Test fun theWrappedBinFolderIsFoundAndReturned() {
        val zip = tmp.newFile("m.zip")
        zip(
            mapOf(
                "sd15_bins/bpe_simple_vocab_16e6.txt" to "vocab".toByteArray(),
                "sd15_bins/unet.bin" to byteArrayOf(1, 2, 3),
                "readme.txt" to "note".toByteArray(),
            ),
            zip,
        )
        val dest = tmp.newFolder("unpacked")
        val dir = ImageZip.unzipTo(zip, dest)
        File(dest, "sd15_bins/unet.bin").let { f -> assertTrue(f.isFile); assertArrayEquals(byteArrayOf(1, 2, 3), f.readBytes()) }
        assertEquals(File(dest, "sd15_bins").canonicalFile, dir.canonicalFile)
        assertTrue(ImageZip.looksLikeModelDir(dest))
    }

    @Test fun aFlatZipUnpacksToTheDestinationItself() {
        val zip = tmp.newFile("m.zip")
        zip(
            mapOf(
                "bpe_simple_vocab_16e6.txt" to "vocab".toByteArray(),
                "text_encoder.bin" to byteArrayOf(9),
            ),
            zip,
        )
        val dest = tmp.newFolder("flat")
        assertEquals(dest.canonicalFile, ImageZip.unzipTo(zip, dest).canonicalFile)
    }

    @Test fun anEntryEscapingTheDestinationIsRejected() {
        val zip = tmp.newFile("evil.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zs ->
            zs.putNextEntry(ZipEntry("../../escaped.txt")); zs.write(byteArrayOf(1)); zs.closeEntry()
        }
        val dest = tmp.newFolder("victim")
        try {
            ImageZip.unzipTo(zip, dest)
            fail("a zip-slip entry must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("unsafe path"))
        }
        assertFalse(File(tmp.root, "escaped.txt").exists())
    }

    @Test fun aZipWithoutATokenizerFallsBackToTheDirWithTheMostBins() {
        val root = tmp.newFolder("plain")
        File(root, "a/unet.bin").let { it.parentFile.mkdirs(); it.writeBytes(byteArrayOf(1)) }
        File(root, "b/unet.bin").let { it.parentFile.mkdirs(); it.writeBytes(byteArrayOf(1)) }
        File(root, "b/vae.bin").let { it.writeBytes(byteArrayOf(2)) }
        assertEquals(File(root, "b").canonicalFile, ImageZip.locateModelDir(root).canonicalFile)
        assertTrue(ImageZip.looksLikeModelDir(root))
        assertFalse(ImageZip.looksLikeModelDir(tmp.newFolder("empty")))
    }

    @Test fun aGibberishZipIsRejectedWithAReadableError() {
        val zip = tmp.newFile("bad.zip")
        zip.writeBytes("this is not a zip file at all".toByteArray())
        val dest = tmp.newFolder("bad-out")
        try {
            ImageZip.unzipTo(zip, dest)
            fail("expected a rejection")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("zip"))
        }
        // No half-unpacked litter stays behind.
        assertTrue(dest.listFiles().orEmpty().isEmpty())
    }
}

class DeviceImageRoutingTest {

    @Test fun theDeviceProviderResolvesToNoProviderAtAll() {
        val sd = ImageModels.resolve("local/image/sd15")
        requireNotNull(sd)
        assertEquals("local/image/sd15", sd.id)
        assertEquals("sd15", sd.slug)
        assertEquals(
            "On-device" to null,
            RoutedImageGenerator.resolve(MediaModelRef(MediaModels.PROVIDER_DEVICE, "local/image/sd15"), emptyList()) { 0 },
        )
    }

    @Test fun anUnknownDeviceModelIdIsDeniedLoudly() {
        try {
            RoutedImageGenerator.resolve(MediaModelRef(MediaModels.PROVIDER_DEVICE, "local/image/nope"), emptyList()) { 0 }
            fail("expected a failure")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("does not exist"))
        }
    }

    @Test fun aNonDeviceProviderIdStillNeedsItsProvider() {
        // The device branch only fires for its own provider id; anything else is untouched.
        try {
            RoutedImageGenerator.resolve(MediaModelRef("device-ish", "x"), emptyList()) { 0 }
            fail("expected a failure")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("not one this app knows"))
        }
    }

    @Test fun importedZipNamesMapStablyToSlugs() {
        val imported = ImageModels.imported("My Model File.zip")
        assertEquals("local/image/file/My Model File.zip", imported.id)
        assertEquals("My Model File.zip", imported.slug)
        assertEquals(imported.id, ImageModels.imported(imported.slug).id)
        assertEquals("My Model File", imported.name)
        assertTrue(ImageModels.isDeviceImageModel(imported.id))
        assertFalse(ImageModels.isDeviceImageModel("openrouter/gpt-image-1"))
    }

    @Test fun theDeviceRefModelIsTheCatalogId() {
        val sd = ImageModels.resolve("local/image/sd15")!!
        assertEquals(sd.id, ImageModels.refModel(sd))
        // Imported models keep their own id (the zip file name), so two models never collide.
        assertEquals(ImageModels.imported("imported.zip").id, ImageModels.refModel(ImageModels.imported("imported.zip")))
    }

    @Test fun dreamshaperShipsInTheCatalogWithAPinnedZip() {
        val ds = ImageModels.resolve("local/image/dreamshaper-8")!!
        assertEquals("dreamshaper-8", ds.slug)
        assertEquals("dreamshaper8-mediapipe.zip", ds.zipName)
        assertTrue(ds.url!!.contains("l3utterfly/sd-mediapipe/resolve/d1d3148a"))
        assertTrue(ds.sizeBytes in 1_500_000_000..2_500_000_000)
        // Catalog ids and slugs must not collide: each unpacks into its own folder.
        assertEquals(catalogSlugs().size, catalogSlugs().distinct().size)
    }

    @Test fun everyCatalogImageModelPinsACommitAndASizeOnItsOwnSlug() {
        ImageModels.all.forEach { m ->
            assertTrue(m.id, m.url!!.contains("/resolve/") && m.url!!.length > m.url!!.indexOf("/resolve/") + 41)
            assertTrue(m.id, m.sizeBytes > 0)
            assertEquals(m.id, m.id, ImageModels.resolve(m.id)!!.id)
        }
    }

    private fun catalogSlugs() = ImageModels.all.map { it.slug }
}

class LocalImageGeneratorTest {

    private class FakeRuntime(
        private val png: ByteArray = byteArrayOf(0x89.toByte(), 0x50.toByte()),
        private val crash: Boolean = false,
    ) : ImageGenRuntime {
        var calls = 0
        var lastPrompt: String? = null
        var lastSteps = 0
        var lastSeed = 0
        var lastDir: File? = null

        override fun generate(modelDir: File, prompt: String, steps: Int, seed: Int): ByteArray {
            calls++
            lastDir = modelDir
            lastPrompt = prompt
            lastSteps = steps
            lastSeed = seed
            if (crash) throw IllegalStateException("boom")
            return png
        }
    }

    @Test fun aReadyModelGeneratesPngBytesOnThePhone() = runBlocking {
        val runtime = FakeRuntime()
        val gen = LocalImageGenerator(
            modelDir = { File("/models/ready") },
            modelName = "SD 1.5",
            runtime = runtime,
            steps = 7,
            randomSeed = { 42 },
        )
        val out = gen.generate("a red apple", "16:9")
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50.toByte()), out.bytes)
        assertEquals("image/png", out.mime)
        assertEquals(1, runtime.calls)
        assertEquals("a red apple", runtime.lastPrompt)
        assertEquals(7, runtime.lastSteps)
        assertEquals(42, runtime.lastSeed)
        assertEquals(File("/models/ready"), runtime.lastDir)
    }

    @Test fun aModelThatIsNotOnThePhoneFailsInsteadOfFallingBack() = runBlocking {
        val runtime = FakeRuntime()
        val gen = LocalImageGenerator(modelDir = { null }, modelName = "SD 1.5", runtime = runtime)
        try {
            gen.generate("x", null)
            fail("expected a failure")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("not downloaded"))
            assertTrue(e.message, e.message!!.contains("On-device"))
        }
        assertEquals(0, runtime.calls)
    }

    @Test fun anEngineCrashBecomesAReadableToolError() = runBlocking {
        val gen = LocalImageGenerator(modelDir = { File("/models/ready") }, modelName = "SD 1.5", runtime = FakeRuntime(crash = true))
        try {
            gen.generate("x", null)
            fail("expected a failure")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("on-device image engine failed"))
            assertTrue(e.message, e.message!!.contains("boom"))
        }
    }
}
