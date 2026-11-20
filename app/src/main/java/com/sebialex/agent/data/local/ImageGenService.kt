package com.sebialex.agent.data.local

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File

/** The message keys shared by [ImageGenService] and [ProcessIsolatedImageGenRuntime]. */
object ImageGenContract {
    const val MSG_GENERATE = 1
    const val MSG_CLOSE = 2
    const val MSG_RESULT_OK = 3
    const val MSG_RESULT_ERROR = 4
    /** An extra reply during MSG_GENERATE: one more diffusion step finished (KEY_STEP of KEY_TOTAL). */
    const val MSG_STEP = 5
    const val KEY_PROMPT = "prompt"
    const val KEY_STEPS = "steps"
    const val KEY_SEED = "seed"
    const val KEY_MODEL_DIR = "model_dir"
    const val KEY_OUT = "out"
    const val KEY_ERROR = "error"
    const val KEY_STEP = "step"
    const val KEY_TOTAL = "total"
    const val KEY_PREVIEW = "preview"

    /** The engine writes its PNG here in the client-visible cache dir (not over Binder limits). */
    const val RESULT_DIR = "imagegen"
}

/**
 * The MediaPipe Image Generator engine in its own process (`:imagegen`, declared in the
 * manifest). Motivation: the engine fails hard on some phones' OpenCL drivers (clCreateImage
 * failures end in a native abort or SIGSEGV), which nothing in Java can catch. Running it in a
 * separate process turns that into a reportable failure — the app survives and shows an error,
 * and the service restarts for the next try.
 *
 * One request at a time (the engine itself is single-client); PNG bytes go to a file the client
 * passes in ([ImageGenContract.KEY_OUT]). After three idle minutes the weights are freed.
 */
class ImageGenService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var work: Handler
    private val runtime by lazy { MediaPipeImageGenRuntime(applicationContext) }
    private val idle = Runnable { runCatching { runtime.close() } }

    private val receiver = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ImageGenContract.MSG_GENERATE -> {
                    work.removeCallbacks(idle)
                    work.post { runGenerate(msg) }
                }
                ImageGenContract.MSG_CLOSE -> {
                    work.removeCallbacks(idle)
                    work.post {
                        runCatching { runtime.close() }
                        finish(msg, ImageGenContract.MSG_RESULT_OK) { Bundle() }
                    }
                }
                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("imagegen").apply { start() }
        work = Handler(worker.looper)
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(receiver).binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        work.removeCallbacks(idle)
        work.looper.quitSafely()
        runCatching { runtime.close() }
        super.onDestroy()
    }

    /** The blocking part, always on the worker thread; every ending replies to the client. */
    private fun runGenerate(msg: Message) {
        try {
            val data = msg.data ?: throw IllegalArgumentException("no request data")
            val dir = File(data.getString(ImageGenContract.KEY_MODEL_DIR).orEmpty())
            if (!dir.isDirectory) throw IllegalArgumentException("the model directory is missing")
            val out = File(data.getString(ImageGenContract.KEY_OUT).orEmpty())
            out.parentFile?.mkdirs()
            out.delete()
            val previewPath = data.getString(ImageGenContract.KEY_PREVIEW).orEmpty()
            val preview = if (previewPath.isEmpty()) null else File(previewPath)
            preview?.delete()
            val bytes = runtime.generate(
                dir,
                data.getString(ImageGenContract.KEY_PROMPT).orEmpty(),
                data.getInt(ImageGenContract.KEY_STEPS, LocalImageGenerator.STEPS),
                data.getInt(ImageGenContract.KEY_SEED),
            ) { step, total, previewBytes ->
                // Preview bytes land in the shared cache dir so the app's UI can show the
                // picture while it is still being drawn; atomically, so a read never sees
                // half a file. Then the step counter follows.
                if (preview != null && previewBytes != null) {
                    runCatching {
                        val tmp = File(preview.parentFile, preview.name + ".tmp")
                        tmp.writeBytes(previewBytes)
                        if (!tmp.renameTo(preview)) preview.writeBytes(previewBytes)
                    }
                }
                sendStep(msg, step, total)
            }
            out.writeBytes(bytes)
            finish(msg, ImageGenContract.MSG_RESULT_OK) { Bundle().apply { putString(ImageGenContract.KEY_OUT, out.absolutePath) } }
        } catch (t: Throwable) {
            finish(msg, ImageGenContract.MSG_RESULT_ERROR) {
                Bundle().apply { putString(ImageGenContract.KEY_ERROR, t.message ?: t.javaClass.simpleName) }
            }
        } finally {
            work.postDelayed(idle, IDLE_UNLOAD_MS)
        }
    }

    private fun finish(request: Message, what: Int, data: () -> Bundle) {
        val client = request.replyTo ?: return
        runCatching {
            Message.obtain(null, what).apply { this.data = data() }.let { client.send(it) }
        }
    }

    /** One finished diffusion step, told to the client from the worker thread mid-generation. */
    private fun sendStep(request: Message, step: Int, total: Int) {
        val client = request.replyTo ?: return
        runCatching {
            Message.obtain(null, ImageGenContract.MSG_STEP).apply {
                data = Bundle().apply {
                    putInt(ImageGenContract.KEY_STEP, step)
                    putInt(ImageGenContract.KEY_TOTAL, total)
                }
            }.let { client.send(it) }
        }
    }

    private companion object {
        /** Weights are ~2 GB of RAM; three quiet minutes and they go back to the disk. */
        const val IDLE_UNLOAD_MS = 3 * 60 * 1000L
    }
}
