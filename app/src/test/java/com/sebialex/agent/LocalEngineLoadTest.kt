package com.sebialex.agent

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.sebialex.agent.data.local.EngineState
import com.sebialex.agent.data.local.LocalEngine.GpuFailure
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The on-device engine's load path: the GPU breaker (out of memory, unsupported operations,
 * clear missing-environment evidence), the model-capabilities cache, load timing and teardown.
 * Everything runs against [FakeLmRuntime], so no native code is involved.
 */
class LocalEngineLoadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val model = LocalModel(
        id = "local/test", name = "Test Model", tagline = "", fileName = "m.litertlm", maxContext = 8192,
    )

    // The same file every time: several engines in one test share it, and a second
    // TemporaryFolder.newFile with the same name would throw because it already exists.
    // It is only written once, so its size and modified time stay stable for the cache tests.
    private fun file(): File = File(tmp.root, "m.litertlm").apply { if (!isFile) writeText("model") }

    private fun engine(
        runtime: FakeLmRuntime,
        context: Int = 4096,
        backend: () -> String = { "gpu" },
        files: ((LocalModel) -> File?)? = null,
        cacheDir: File = tmp.newFolder(),
    ): LocalEngine {
        // One stable file per engine, so the capabilities cache can be exercised.
        val f = file()
        return LocalEngine(runtime, files ?: { f }, cacheDir, backend, { context }, { false }, CoroutineScope(Dispatchers.Default))
    }

    // ------------------------------------------------------------------ GPU out of memory

    @Test
    fun gpuMemoryFailureFailsTheLoadWithoutACpuEngineAndRetrySucceedsOnGpu() = runBlocking {
        val runtime = FakeLmRuntime(gpuFailure = OutOfMemoryError("out of memory"))
        val engine = engine(runtime)
        try {
            engine.load(model)
            fail("expected the load to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("not enough memory"))
        }
        // No CPU engine was opened as a fallback.
        assertEquals(1, runtime.gpuOpens.get())
        assertEquals(0, runtime.cpuOpens.get())
        val failed = engine.state.value as EngineState.Failed
        assertEquals("not enough memory", failed.message)
        // The breaker was not tripped: a retry tries the GPU again, and succeeds there.
        runtime.gpuFailure = null
        val loaded = engine.load(model)
        assertEquals("GPU", loaded.backend)
        assertEquals(2, runtime.gpuOpens.get())
        assertEquals(0, runtime.cpuOpens.get())
    }

    @Test
    fun openclResourceCodesAreMemoryFailures() = runBlocking {
        val runtime = FakeLmRuntime()
        val engine = engine(runtime)
        // The literal OpenCL resource codes: a GPU out of memory, not a broken environment.
        assertEquals(GpuFailure.MEMORY, engine.classifyGpuFailure(RuntimeException("CL_OUT_OF_RESOURCES executing kernel")))
        assertEquals(GpuFailure.MEMORY, engine.classifyGpuFailure(RuntimeException("CL_OUT_OF_HOST_MEMORY during buffer allocation")))
        // So the load fails without a CPU engine, and Retry tries the GPU again.
        runtime.gpuFailure = RuntimeException("CL_OUT_OF_RESOURCES executing kernel")
        try {
            engine.load(model)
            fail("expected the load to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("not enough memory"))
        }
        assertEquals(1, runtime.gpuOpens.get())
        assertEquals(0, runtime.cpuOpens.get())
        assertEquals("not enough memory", (engine.state.value as EngineState.Failed).message)
        runtime.gpuFailure = null
        assertEquals("GPU", engine.load(model).backend)
    }

    @Test
    fun openclAllocationFailureMessageIsTreatedAsNotEnoughMemory() = runBlocking {
        val runtime = FakeLmRuntime(gpuFailure = RuntimeException("CL_MEM_OBJECT_ALLOCATION_FAILURE during buffer creation"))
        val engine = engine(runtime)
        try {
            engine.load(model)
            fail("expected the load to fail")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("not enough memory"))
        }
        assertEquals(0, runtime.cpuOpens.get())
        assertTrue(engine.state.value is EngineState.Failed)
    }

    // ------------------------------------------------------------------ GPU breaker

    @Test
    fun unsatisfiedLinkErrorAlwaysLatchesTheBreaker() = runBlocking {
        val runtime = FakeLmRuntime(gpuFailure = UnsatisfiedLinkError("liblitertlm_jni.so not found"))
        val engine = engine(runtime)
        // The classifier never lets a linkage failure through, whatever the message says.
        assertEquals(GpuFailure.PERSISTENT, engine.classifyGpuFailure(UnsatisfiedLinkError("anything at all")))
        val loaded = engine.load(model)
        assertEquals("CPU", loaded.backend)
        // The next load does not try the GPU again (the breaker is latched for this app run).
        engine.unload()
        val again = engine.load(model)
        assertEquals("CPU", again.backend)
        assertEquals(1, runtime.gpuOpens.get())
        assertEquals(2, runtime.cpuOpens.get())
    }

    @Test
    fun missingOpenclEnvironmentLatchesTheBreakerUntilTheAppRestarts() = runBlocking {
        val runtime = FakeLmRuntime(gpuFailure = RuntimeException("failed to create OpenCL environment"))
        val engine = engine(runtime)
        assertEquals(GpuFailure.PERSISTENT, engine.classifyGpuFailure(RuntimeException("failed to create OpenCL environment")))
        assertEquals("CPU", engine.load(model).backend)
        engine.unload()
        assertEquals("CPU", engine.load(model).backend)
        assertEquals(1, runtime.gpuOpens.get())
        // A new app run (a new engine) starts with a clean breaker and tries the GPU again.
        runtime.gpuFailure = null
        assertEquals("GPU", engine(runtime).load(model).backend)
    }

    @Test
    fun modelWordInAnOpenclMessageNeverLatchesTheBreaker() = runBlocking {
        val runtime = FakeLmRuntime(gpuFailure = RuntimeException("no OpenCL support for this model's tensors"))
        val engine = engine(runtime)
        // The message mentions OpenCL, but it is about the model, not the environment.
        assertEquals(GpuFailure.TRANSIENT, engine.classifyGpuFailure(RuntimeException("no OpenCL support for this model's tensors")))
        assertEquals(GpuFailure.TRANSIENT, engine.classifyGpuFailure(RuntimeException("this tensor shape is not supported by the GPU")))
        assertEquals(GpuFailure.TRANSIENT, engine.classifyGpuFailure(RuntimeException("the model metadata section is invalid")))
        val loaded = engine.load(model)
        assertEquals("CPU", loaded.backend)
        // Not latched: the next load tries the GPU again.
        runtime.gpuFailure = null
        engine.unload()
        assertEquals("GPU", engine.load(model).backend)
    }

    @Test
    fun unsupportedGpuOperationFallsBackButTheNextLoadStillTriesTheGpu() = runBlocking {
        val runtime = FakeLmRuntime(gpuFailure = RuntimeException("GPU delegate does not support operation XYZ"))
        val engine = engine(runtime)
        assertEquals(GpuFailure.TRANSIENT, engine.classifyGpuFailure(RuntimeException("GPU delegate does not support operation XYZ")))
        assertEquals("CPU", engine.load(model).backend)
        runtime.gpuFailure = null
        engine.unload()
        assertEquals("GPU", engine.load(model).backend)
        assertEquals(2, runtime.gpuOpens.get())
    }

    @Test
    fun openclKernelCompilationFailureFallsBackButTheNextLoadStillTriesTheGpu() = runBlocking {
        val runtime = FakeLmRuntime(gpuFailure = RuntimeException("OpenCL kernel compilation failed"))
        val engine = engine(runtime)
        assertEquals("CPU", engine.load(model).backend)
        runtime.gpuFailure = null
        engine.unload()
        assertEquals("GPU", engine.load(model).backend)
    }

    // ------------------------------------------------------------------ capabilities cache

    @Test
    fun capabilitiesAreCachedByFileStampAcrossLoads() = runBlocking {
        val runtime = FakeLmRuntime()
        val engine = engine(runtime)
        engine.load(model)
        assertEquals(1, runtime.modelInfoCalls.get())
        engine.unload()
        engine.load(model)
        // The same file (path, size, modified time) skips the read entirely.
        assertEquals(1, runtime.modelInfoCalls.get())
    }

    @Test
    fun capabilitiesAreReadFromDiskAfterARestart() = runBlocking {
        val cacheDir = tmp.newFolder()
        val f = file()
        val first = engine(FakeLmRuntime(), files = { f }, cacheDir = cacheDir)
        first.load(model)
        assertTrue(File(cacheDir, "model_capabilities.json").isFile)
        // A new app run, a new runtime, the same file: the persisted cache answers without
        // reading the file again.
        val runtime = FakeLmRuntime()
        engine(runtime, files = { f }, cacheDir = cacheDir).load(model)
        assertEquals(0, runtime.modelInfoCalls.get())
    }

    @Test
    fun changedFileStampRereadsCapabilities() = runBlocking {
        val runtime = FakeLmRuntime()
        val f = file()
        val engine = engine(runtime, files = { f })
        engine.load(model)
        assertEquals(1, runtime.modelInfoCalls.get())
        f.writeText("model v2")
        f.setLastModified(f.lastModified() + 60_000)
        engine.unload()
        engine.load(model)
        assertEquals(2, runtime.modelInfoCalls.get())
    }

    // ------------------------------------------------------------------ timing and teardown

    @Test
    fun loadMillisCoversMetadataAndBothBackends() = runBlocking {
        val runtime = FakeLmRuntime(
            gpuFailure = RuntimeException("failed to create OpenCL environment"),
            modelInfoDelayMs = 60,
            openDelayMs = 60,
        )
        val loaded = engine(runtime).load(model)
        // The metadata read, the failed GPU attempt and the CPU fallback are all inside.
        assertTrue("loadMillis=${loaded.loadMillis}", loaded.loadMillis >= 175)
    }

    @Test
    fun unloadClosesTheConversationBeforeTheEngineEvenWhenCancelled() = runBlocking {
        val runtime = FakeLmRuntime(closeLagMs = 150)
        val engine = engine(runtime)
        engine.withEngine(model) { _, session ->
            session.open("run|", null, ConversationConfig(systemInstruction = Contents.of("sys")))
        }
        assertTrue(runtime.conversations.isNotEmpty())
        // The caller is cancelled while the unload runs; it must still finish, in order.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.unload() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals(listOf("conversation", "engine"), runtime.closeLog)
        assertTrue(runtime.conversations.single().closed.get())
        assertTrue(runtime.engines.single().closed.get())
        assertTrue(engine.state.value is EngineState.Idle)
    }
}
