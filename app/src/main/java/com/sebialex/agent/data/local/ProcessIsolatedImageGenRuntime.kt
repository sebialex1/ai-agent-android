package com.sebialex.agent.data.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ImageGenRuntime] backed by [ImageGenService] in its own process (`:imagegen`). The MediaPipe
 * engine can die a native death (SIGSEGV / abort in OpenCL drivers, seen on Samsung Xclipse and
 * other Galaxy GPUs) that no Java catch can stop; in a separate process the app survives, and
 * the disconnected service connection surfaces as a readable error here. PNG bytes return via a
 * file the service writes ([ImageGenContract.RESULT_DIR] in the app cache), never over Binder.
 *
 * One request at a time ([lock]); the service keeps the weights loaded between requests and
 * frees them after its own idle timeout.
 */
class ProcessIsolatedImageGenRuntime(private val context: Context) : ImageGenRuntime {

    private val lock = Any()
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null

    @Volatile
    private var crashed = false
    private var replyLatch: CountDownLatch? = null

    @Volatile
    private var reply: Message? = null

    /** Progress tracking for the watchdog: the last step number the service reported, and when. */
    @Volatile
    private var lastStep = 0

    @Volatile
    private var lastStepAt = 0L

    /** The generate() caller's step callback, while one generation is in flight (one at a time). */
    @Volatile
    private var stepListener: ((step: Int, total: Int, preview: ByteArray?) -> Unit)? = null

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == ImageGenContract.MSG_STEP) {
                // Mid-generation progress. The step's preview picture travels the shared cache
                // dir next to the result (not Binder); the callback gets no bytes of its own.
                val data = msg.data ?: return
                lastStep = data.getInt(ImageGenContract.KEY_STEP)
                lastStepAt = System.currentTimeMillis()
                stepListener?.invoke(lastStep, data.getInt(ImageGenContract.KEY_TOTAL), null)
                return
            }
            reply = Message.obtain(msg)
            replyLatch?.countDown()
        }
    }

    override fun generate(
        modelDir: File,
        prompt: String,
        steps: Int,
        seed: Int,
        onStep: (step: Int, total: Int, preview: ByteArray?) -> Unit,
    ): ByteArray = synchronized(lock) {
        // Step 0: the chat shows "starting the engine…" right away — the real first step can be
        // minutes behind (weights load, GPU graph compile), and silence there looked like death.
        onStep(0, steps, null)
        stepListener = onStep
        try {
            generateLocked(modelDir, prompt, steps, seed)
        } finally {
            stepListener = null
        }
    }

    private fun generateLocked(modelDir: File, prompt: String, steps: Int, seed: Int): ByteArray {
        bind()
        val out = File(context.cacheDir, ImageGenContract.RESULT_DIR + "/result.png")
        val preview = File(context.cacheDir, ImageGenContract.RESULT_DIR + "/preview.jpg")
        preview.delete()
        val data = Bundle().apply {
            putString(ImageGenContract.KEY_PROMPT, prompt)
            putInt(ImageGenContract.KEY_STEPS, steps)
            putInt(ImageGenContract.KEY_SEED, seed)
            putString(ImageGenContract.KEY_MODEL_DIR, modelDir.absolutePath)
            putString(ImageGenContract.KEY_OUT, out.absolutePath)
            putString(ImageGenContract.KEY_PREVIEW, preview.absolutePath)
        }
        val answer = ask(ImageGenContract.MSG_GENERATE, data, GENERATE_TIMEOUT_MS)
        return when (answer.what) {
            ImageGenContract.MSG_RESULT_OK ->
                out.takeIf { it.isFile && it.length() > 0 }?.readBytes()
                    ?: throw IOException("the image engine returned no image")
            else -> throw IOException(answer.data?.getString(ImageGenContract.KEY_ERROR) ?: "the image engine failed")
        }
    }

    override fun close() = synchronized(lock) {
        if (service != null) runCatching { ask(ImageGenContract.MSG_CLOSE, null, ASK_TIMEOUT_MS) }
        Unit
    }

    /** Connected (and started, so a crash is restarted), within a short timeout. */
    private fun bind() {
        if (service != null && !crashed) return
        crashed = false
        // startService first: keeps the process alive and restarts it after a native abort.
        runCatching { context.startService(Intent(context, ImageGenService::class.java)) }
        val connected = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = Messenger(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                crashed = true
                service = null
                replyLatch?.countDown()
            }
        }
        connection = conn
        if (!context.bindService(Intent(context, ImageGenService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            connection = null
            throw IOException("Could not start the image engine process.")
        }
        try {
            if (!connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("The image engine did not start. Try again.")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while starting the image engine.")
        }
    }

    /**
     * Sends [what] and waits for the service's reply (up to [timeoutMs]). The wait is sliced:
     * a native crash, or silence from the engine (which on a GPU that cannot run it means
     * hanging forever inside the driver), turns into a readable error in minutes instead of
     * sitting out the full timeout quietly.
     */
    private fun ask(what: Int, data: Bundle?, timeoutMs: Long): Message {
        val target = service ?: throw IOException("The image engine is not available. Try again.")
        val startedAt = System.currentTimeMillis()
        lastStep = 0
        lastStepAt = 0L
        reply = null
        val latch = CountDownLatch(1)
        synchronized(this) { replyLatch = latch }
        try {
            val request = Message.obtain(null, what).apply {
                if (data != null) this.data = data
                replyTo = Messenger(replyHandler)
            }
            runCatching { target.send(request) }.onFailure {
                throw IOException("Could not reach the image engine: ${it.message ?: it.javaClass.simpleName}")
            }
            while (true) {
                // onServiceDisconnected counts the same latch down; a crash shows up here.
                if (latch.await(WATCHDOG_SLICE_MS, TimeUnit.MILLISECONDS)) break
                if (crashed) {
                    throw IOException("The image engine crashed — this phone's GPU probably cannot run it (MediaPipe ships no CPU mode for it). Pick a cloud image model in Settings > Models.")
                }
                val silentFor = System.currentTimeMillis() - (if (lastStep > 0) lastStepAt else startedAt)
                if (lastStep <= 0 && silentFor > INIT_STALL_MS) {
                    throw IOException("The image engine did not start — nothing came back in ${INIT_STALL_MS / 1000 / 60} minutes. This phone's GPU driver probably cannot run it (MediaPipe ships no CPU mode for it). Pick a cloud image model in Settings > Models.")
                }
                if (lastStep > 0 && silentFor > STEP_STALL_MS) {
                    throw IOException("The image engine stopped responding at step $lastStep — the GPU driver probably cannot finish the picture. Pick a cloud image model in Settings > Models.")
                }
                val waited = System.currentTimeMillis() - startedAt
                if (waited > timeoutMs) {
                    throw IOException("The image engine did not answer in time (${timeoutMs / 1000 / 60} min). Try again.")
                }
            }
        } finally {
            synchronized(this) { replyLatch = null }
        }
        return reply ?: throw IOException("The image engine answered with nothing.")
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
        const val ASK_TIMEOUT_MS = 30_000L

        /** Slow phones are slow; MediaPipe itself has no cancel. 15 minutes per picture. */
        const val GENERATE_TIMEOUT_MS = 15 * 60 * 1000L

        /** How often the watchdog wakes to check progress. */
        const val WATCHDOG_SLICE_MS = 5_000L

        /** Weights load + GPU graph compile; slow on flash and cold caches, but not minutes. */
        const val INIT_STALL_MS = 4 * 60 * 1000L

        /** Once steps flow, one step should not take longer than a couple of minutes. */
        const val STEP_STALL_MS = 2 * 60 * 1000L
    }
}
