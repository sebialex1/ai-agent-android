package com.sebialex.agent

import com.sebialex.agent.data.local.ContextCeiling
import com.sebialex.agent.data.local.GpuCrashMemo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The guards that keep a native engine from killing the app: a context window sized to the
 * phone's memory before the engine exists, and the crash memo that skips the GPU after a run
 * died inside a model (a native crash leaves nothing to catch, so the phone's exit history
 * decides). Both are pure and checked here; the probes around them just read the system.
 */
class DeviceGuardTest {

    private val GiB = 1L shl 30

    @Test
    fun perTokenCostIsScaledFromTheModelButNeverBelowTheFloor() {
        assertEquals(64L shl 10, ContextCeiling.bytesPerToken(100_000))
        assertEquals(115_200, ContextCeiling.bytesPerToken(20_000L * 115_200))
        assertEquals(64L shl 10, ContextCeiling.bytesPerToken(1_000_000))
    }

    @Test
    fun unknownMemoryNeverCaps() {
        assertEquals(Int.MAX_VALUE, ContextCeiling.deviceLimit(2 * GiB, 0))
        assertEquals(4096, ContextCeiling.effective(4096, 2 * GiB, 0))
        assertEquals(Int.MAX_VALUE, ContextCeiling.deviceLimit(0, 8 * GiB))
    }

    @Test
    fun aSmallPhoneKeepsOnlyWhatItCanHold() {
        // 8 GB phone, 2.5 GB model: half of RAM minus the model minus ~1 GB runtime overhead.
        val limit = ContextCeiling.deviceLimit((2.5 * GiB).toLong(), 8 * GiB)
        assertTrue("a 2.5 GB model on 8 GB caps well below 8K, got $limit", limit in 2048..4096)
        assertTrue(limit % 1024 == 0)
    }

    @Test
    fun aBigPhoneCapsGenerously() {
        // 12 GB phone, 2.6 GB model: measured on the reference phone, 10-15K ran, 20K died.
        val limit = ContextCeiling.deviceLimit((2.6 * GiB).toLong(), 12 * GiB)
        assertTrue("expected a cap in the high teens of K, got $limit", limit in 12_288..20_480)
    }

    @Test
    fun aPhoneThatCanBarelyHoldTheModelFallsBackToTheFloor() {
        assertEquals(2_048, ContextCeiling.deviceLimit(1 * GiB, 2 * GiB))
    }

    @Test
    fun theCeilingNeverRaisesARequestedContext() {
        assertEquals(1024, ContextCeiling.effective(1024, 2 * GiB, 64 * GiB))
        assertEquals(4096, ContextCeiling.effective(4096, 2 * GiB, 64 * GiB))
        // ...and only ever lowers it.
        assertTrue(ContextCeiling.effective(16_384, (2.5 * GiB).toLong(), 8 * GiB) < 16_384)
    }

    @Test
    fun aCrashInsideTheLoadedSpanSkipsTheGpuNextRun() {
        assertTrue(GpuCrashMemo.shouldSkipGpu(listOf(1_000L, 5_000L), 4_000L))
        assertFalse("crashes from before the model was loaded are not its fault", GpuCrashMemo.shouldSkipGpu(listOf(1_000L), 4_000L))
        assertFalse(GpuCrashMemo.shouldSkipGpu(emptyList(), 4_000L))
    }

    @Test
    fun theMarkerMemoryRealisticOrdering() {
        // A stale marker older than every recorded crash cannot match the newest one inside it.
        val dir = Files.createTempDirectory("memo").toFile()
        val marker = File(dir, "m")
        marker.writeText("m")
        marker.setLastModified(System.currentTimeMillis() - 60_000)
        assertTrue(GpuCrashMemo.shouldSkipGpu(listOf(System.currentTimeMillis()), marker.lastModified()))
        assertFalse(GpuCrashMemo.shouldSkipGpu(listOf(marker.lastModified() - 60_000), marker.lastModified()))
    }
}
