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

    /** The generate() caller's step callback, while one generation is in flight (one at a time). */
    @Volatile
    private var stepListener: ((step: Int, total: Int, preview: ByteArray?) -> Unit)? = null

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == ImageGenContract.MSG_STEP) {
                // Mid-generation progress. The step's preview picture travels the shared cache
                // dir next to the result (not Binder); the callback gets no bytes of its own.
                val data = msg.data ?: return
                stepListener?.invoke(data.getInt(ImageGenContract.KEY_STEP), data.getInt(ImageGenContract.KEY_TOTAL), null)
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

    /** Sends [what] and waits for the service's reply (up to [timeoutMs]). */
    private fun ask(what: Int, data: Bundle?, timeoutMs: Long): Message {
        val target = service ?: throw IOException("The image engine is not available. Try again.")
        reply = null
        val latch = CountDownLatch(1)
        synchronized(this) { replyLatch = latch }
        val request = Message.obtain(null, what).apply {
            if (data != null) this.data = data
            replyTo = Messenger(replyHandler)
        }
        runCatching { target.send(request) }.onFailure {
            throw IOException("Could not reach the image engine: ${it.message ?: it.javaClass.simpleName}")
        }
        // onServiceDisconnected counts the same latch down; a crash shows up as one of these.
        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(this) { replyLatch = null }
        if (crashed && !done) throw IOException("The image engine crashed — this phone's GPU probably cannot run it. Pick a cloud image model in Settings > Models.")
        if (!done) throw IOException("The image engine did not answer in time (${timeoutMs / 1000 / 60} min). Try again.")
        return reply ?: throw IOException("The image engine answered with nothing.")
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
        const val ASK_TIMEOUT_MS = 30_000L
        /** Slow phones are slow; MediaPipe itself has no cancel. 15 minutes per picture. */
        const val GENERATE_TIMEOUT_MS = 15 * 60 * 1000L
    }
}
