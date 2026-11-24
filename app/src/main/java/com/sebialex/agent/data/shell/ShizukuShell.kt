package com.sebialex.agent.data.shell

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * ADB-level shell access through Shizuku, abstracted behind [ShizukuGate] so the workspace shell
 * (`SandboxShell`) stays JVM-testable: the real binder traffic lives only in [ShizukuRunner].
 */
interface ShizukuGate {
    /**
     * True when the user turned Shizuku on for commands, the Shizuku binder is alive and the
     * permission is granted. Re-read on every command, so a settings toggle takes effect at once.
     */
    fun ready(): Boolean

    /**
     * Runs a full command line with shell privileges (like adb shell): an absolute working
     * directory, the app's timeout budget, output and exit code back. Throws only when Shizuku
     * itself cannot be reached; a failing command arrives as a normal failed result.
     */
    fun run(command: String, cwd: String, timeoutSeconds: Long): ShellResult
}

/**
 * The real gate over the Shizuku binder (dev.rikka.shizuku). Commands go through the public
 * `IShizukuService.newProcess` AIDL (the convenience `Shizuku.newProcess` is private in API 13).
 * Every call is guarded: an absent, stopped or denying Shizuku is never a crash, only "not ready".
 */
class ShizukuRunner(private val enabled: () -> Boolean) : ShizukuGate {

    override fun ready(): Boolean =
        enabled() && runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    override fun run(command: String, cwd: String, timeoutSeconds: Long): ShellResult {
        val remote = newRemoteProcess(command, cwd)
        val output = StringBuilder()
        fun drain(stream: ParcelFileDescriptor): Thread = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(stream).bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(this@ShizukuRunner) { if (output.length < MAX_OUTPUT) output.append(buf, 0, n) }
                    }
                }
            }
        }
        // Both streams belong in the answer (the shell uid's errors are diagnostics).
        val stdoutReader = drain(remote.inputStream).apply { start() }
        val reader = drain(remote.errorStream).apply { start() }
        // IRemoteProcess.waitFor() would block forever: poll alive() against the shared budget.
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (runCatching { remote.alive() }.getOrDefault(false) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        val finished = runCatching { !remote.alive() }.getOrDefault(false)
        if (!finished) runCatching { remote.destroy() }
        stdoutReader.join(2_000)
        reader.join(2_000)
        var text = synchronized(this@ShizukuRunner) { output.toString() }
        if (text.length >= MAX_OUTPUT) text = text.take(MAX_OUTPUT) + "\n[output truncated]"
        val code = if (finished) runCatching { remote.exitValue() }.getOrDefault(1) else 124
        if (code == 124) text = text.trimEnd() + "\n[timed out after ${timeoutSeconds}s]"
        return ShellResult(text.trimEnd('\n'), code, cwd)
    }

    private fun viaFailed(message: String): Nothing = throw java.io.IOException(message)

    /** The one service call: binder wrapped for Shizuku, deferred permission check inside. */
    private fun newRemoteProcess(command: String, cwd: String): IRemoteProcess =
        try {
            val binder = ShizukuBinderWrapper(Shizuku.getBinder() ?: return viaFailed("Shizuku is not connected"))
            val service = IShizukuService.Stub.asInterface(binder)
            service.newProcess(arrayOf("sh", "-c", command), null, cwd)
        } catch (e: Exception) {
            throw java.io.IOException(e.message ?: "Shizuku refused to start a process")
        }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 1
        private const val MAX_OUTPUT = 64_000

        fun installed(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

        fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun granted(): Boolean =
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

        fun requestPermission() = runCatching { Shizuku.requestPermission(REQUEST_CODE) }

        /** Registers for the grant/deny result; pass the listener back to unlisten() on dispose. */
        fun listen(onResult: (Boolean) -> Unit): Shizuku.OnRequestPermissionResultListener? {
            val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
            // Guarded: the listener registration is binder traffic too, and Shizuku may not even
            // be installed when this screen is visible.
            return if (runCatching { Shizuku.addRequestPermissionResultListener(listener) }.isSuccess) listener else null
        }

        fun unlisten(listener: Shizuku.OnRequestPermissionResultListener?) =
            listener?.let { runCatching { Shizuku.removeRequestPermissionResultListener(it) } }
    }
}
