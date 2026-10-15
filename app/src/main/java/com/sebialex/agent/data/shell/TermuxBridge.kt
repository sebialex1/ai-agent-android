package com.sebialex.agent.data.shell

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Why the Termux environment cannot run a command right now. Null means ready. */
enum class TermuxProblem(val message: String) {
    NOT_INSTALLED(
        "Termux is not installed. Install Termux (F-Droid or GitHub build), open it once so it can " +
            "set itself up, then try again.",
    ),
    NO_RUN_PERMISSION(
        "permission denied: this app is not allowed to run commands in Termux. Grant " +
            "\"Run commands in Termux environment\" in Settings > General > Sandbox.",
    ),
    NO_STORAGE(
        "permission denied: shared storage access is off, so the workspace cannot be shared with " +
            "Termux. Allow \"All files access\" in Settings > General > Sandbox.",
    ),
}

data class TermuxStatus(
    val installed: Boolean,
    val runPermission: Boolean,
    val storage: Boolean,
    /**
     * Termux's own `allow-external-apps` setting. This app cannot read Termux's private files,
     * so it is only known after Termux has answered a command: true once a command ran, false
     * when Termux rejected one for this reason, null while unknown. Not part of [problem] so a
     * stale "false" never stops the next attempt (the user may have just fixed it).
     */
    val externalApps: Boolean? = null,
) {
    val problem: TermuxProblem? = when {
        !installed -> TermuxProblem.NOT_INSTALLED
        !runPermission -> TermuxProblem.NO_RUN_PERMISSION
        !storage -> TermuxProblem.NO_STORAGE
        else -> null
    }
    val ready get() = problem == null
}

/** Raw result of one RUN_COMMAND execution as returned by Termux. */
data class TermuxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    /** Termux internal error code; -1 (Activity.RESULT_OK) means none. */
    val err: Int,
    val errmsg: String,
    val stdoutTruncated: Boolean,
)

class TermuxException(message: String) : Exception(message)

/** The part of [TermuxBridge] the sandbox shell runs commands through (a fake in tests). */
interface TermuxRunner {
    fun status(): TermuxStatus
    fun run(script: String, workdir: String, timeoutSeconds: Long, label: String = "agent command"): TermuxResult
}

/**
 * Runs commands inside the real Termux environment (its own bash, apt/dpkg, pkg, Python, pip)
 * through Termux's documented `RUN_COMMAND` intent (`com.termux.app.RunCommandService`).
 * The result comes back through a one-shot PendingIntent to [TermuxResultReceiver].
 *
 * Requirements on the device (all checked and reported, never silently skipped):
 * - Termux installed (`com.termux`), opened once so its bootstrap is installed.
 * - `allow-external-apps = true` in `~/.termux/termux.properties`.
 * - The `com.termux.permission.RUN_COMMAND` permission granted to this app.
 * - Shared storage access, because Termux runs as a different Linux user and cannot see this
 *   app's private files; each conversation's workspace is mirrored to [sharedRoot].
 */
class TermuxBridge(private val context: Context) : TermuxRunner {

    override fun status(): TermuxStatus = TermuxStatus(
        installed = isInstalled(),
        runPermission = context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED,
        storage = hasStorageAccess(),
        externalApps = externalAppsAllowed,
    )

    /** Last thing Termux told us about `allow-external-apps`; see [TermuxStatus.externalApps]. */
    @Volatile
    var externalAppsAllowed: Boolean? = null
        private set

    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Shared directory that both this app and Termux can read and write. One subfolder per conversation. */
    val sharedRoot: File get() = File(Environment.getExternalStorageDirectory(), SHARED_DIR_NAME)

    /**
     * Runs [script] with Termux's bash, in [workdir], in the background, and waits for the result.
     * The script is wrapped in coreutils `timeout`, so it is killed after [timeoutSeconds].
     * Throws [TermuxException] when Termux cannot be reached or does not answer.
     */
    override fun run(script: String, workdir: String, timeoutSeconds: Long, label: String): TermuxResult {
        status().problem?.let { throw TermuxException(it.message) }
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<Bundle>()
        pending[id] = future
        try {
            val callback = Intent(context, TermuxResultReceiver::class.java)
                .setAction(ACTION_RESULT)
                .putExtra(EXTRA_EXECUTION_ID, id)
            val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, callback, flags)
            // `timeout` comes from Termux's coreutils. Exit 124 means it was killed.
            val wrapper = "export DEBIAN_FRONTEND=noninteractive PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_INPUT=1; " +
                "exec timeout -k 5 $timeoutSeconds bash -c \"\$1\""
            val intent = Intent(ACTION_RUN_COMMAND)
                .setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                .putExtra(EXTRA_COMMAND_PATH, BASH)
                .putExtra(EXTRA_ARGUMENTS, arrayOf("-c", wrapper, "agent", script))
                .putExtra(EXTRA_WORKDIR, workdir)
                .putExtra(EXTRA_BACKGROUND, true)
                .putExtra(EXTRA_COMMAND_LABEL, label)
                .putExtra(EXTRA_PENDING_INTENT, pi)
            try {
                context.startForegroundService(intent)
            } catch (e: SecurityException) {
                throw TermuxException("permission denied: Termux refused the command (${e.message}). " + TermuxProblem.NO_RUN_PERMISSION.message)
            } catch (e: IllegalStateException) {
                throw TermuxException("Termux could not be started: ${e.message}")
            }
            val bundle = try {
                future.get(timeoutSeconds + RESULT_GRACE_SECONDS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                throw TermuxException(
                    "Termux did not return a result within ${timeoutSeconds + RESULT_GRACE_SECONDS}s. " +
                        "Open Termux once, check that allow-external-apps = true is set in ~/.termux/termux.properties, " +
                        "and exempt Termux from battery optimisation.",
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw TermuxException("interrupted")
            }
            val stdoutOriginal = bundle.getString(RESULT_STDOUT_ORIGINAL_LENGTH)?.toIntOrNull()
            val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
            val err = bundle.getInt(RESULT_ERR, -1)
            var errmsg = bundle.getString(RESULT_ERRMSG).orEmpty()
            // RunCommandService checks allow-external-apps against the properties Termux loaded
            // in memory (TermuxAppSharedProperties), not the file on disk. Editing the file is
            // not enough until Termux reloads it, so say exactly how to get past this.
            if (err != -1 && errmsg.contains(PROP_ALLOW_EXTERNAL_APPS)) {
                externalAppsAllowed = false
                errmsg = errmsg.trim() + "\n" + EXTERNAL_APPS_HINT
            } else if (err == -1) {
                externalAppsAllowed = true
            }
            return TermuxResult(
                stdout = stdout,
                stderr = bundle.getString(RESULT_STDERR).orEmpty(),
                exitCode = bundle.getInt(RESULT_EXIT_CODE, -1),
                err = err,
                errmsg = errmsg,
                stdoutTruncated = stdoutOriginal != null && stdoutOriginal > stdout.length,
            )
        } finally {
            pending.remove(id)
        }
    }

    internal companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val BASH = "$TERMUX_PREFIX/bin/bash"
        const val SHARED_DIR_NAME = "AgentSandbox"
        const val PROP_ALLOW_EXTERNAL_APPS = "allow-external-apps"
        const val EXTERNAL_APPS_HINT =
            "Fix: open Termux and run the setup command from Settings > General > Sandbox. If it was already run, Termux " +
                "is still using its old settings: run termux-reload-settings in Termux, or force stop Termux " +
                "(App info > Force stop), open it again, then tap Check."

        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val RESULT_BUNDLE = "result"
        const val RESULT_STDOUT = "stdout"
        const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
        const val RESULT_STDERR = "stderr"
        const val RESULT_EXIT_CODE = "exitCode"
        const val RESULT_ERR = "err"
        const val RESULT_ERRMSG = "errmsg"

        const val ACTION_RESULT = "com.sebialex.agent.TERMUX_RESULT"
        const val EXTRA_EXECUTION_ID = "execution_id"
        const val RESULT_GRACE_SECONDS = 20L

        private val nextId = AtomicInteger(1000)
        val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    }
}

/** Receives RUN_COMMAND results from Termux and hands them to the waiting command. Not exported. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxBridge.EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra(TermuxBridge.RESULT_BUNDLE) ?: Bundle().apply {
            putInt(TermuxBridge.RESULT_ERR, 1)
            putString(TermuxBridge.RESULT_ERRMSG, "Termux returned no result bundle")
        }
        TermuxBridge.pending[id]?.complete(bundle)
    }
}
