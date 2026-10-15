package com.sebialex.agent.data.shell

import java.io.File

/**
 * The Termux setup check of Settings > General > Termux sandbox, with the same readiness rules
 * as running a command: it only reaches Termux when [TermuxRunner.status] is ready (the gate
 * [SandboxShell] uses to pick Termux over the device shell), looks programs up with the same
 * [ToolCheck] probe that guards every command, and reads the answer with [ToolCheck.parse]. So a
 * "Verified" here means the agent's commands will find the same programs, and a problem shown
 * here is the one a command would hit.
 */
object TermuxSetupCheck {
    /** What the setup command provides and the agent relies on. */
    val PROGRAMS = listOf("bash", "apt", "dpkg", "pkg", "python", "pip")

    private const val SHARED_OK = "shared workspace: ok"

    /**
     * [ok]: Termux answered, every program was found and the shared workspace is writable.
     * [ran]: Termux was asked at all (false when the status was not ready). [text]: the report.
     */
    data class Result(val ok: Boolean, val text: String, val ran: Boolean)

    fun run(termux: TermuxRunner, sharedRoot: File): Result {
        // Exactly the runtime gate: not ready means commands would not go to Termux either.
        termux.status().problem?.let { return Result(ok = false, text = it.message, ran = false) }
        val shared = sharedRoot.apply { mkdirs() }.absolutePath
        val q = SandboxShell.shellQuote(shared)
        val script = "echo 'termux ok'; " + ToolCheck.script(PROGRAMS) + "; " +
            // Also proves Termux can write the shared workspace root, which every mirrored command needs.
            "if touch $q/.agent-check 2>/dev/null && rm -f $q/.agent-check; then echo '$SHARED_OK'; " +
            "else echo 'shared workspace: not writable from Termux (run termux-setup-storage in Termux)'; fi"
        val r = try {
            termux.run(script, workdir = "~/", timeoutSeconds = SandboxShell.TIMEOUT_SECONDS, label = "agent environment check")
        } catch (e: TermuxException) {
            return Result(ok = false, text = e.message ?: "Termux did not answer", ran = true)
        }
        if (r.err != -1 && r.errmsg.isNotBlank()) return Result(ok = false, text = "termux error ${r.err}: ${r.errmsg.trim()}", ran = true)
        val out = r.stdout + "\n" + r.stderr
        val lines = out.lineSequence().map { it.trim() }.toList()
        val found = ToolCheck.parse(out, PROGRAMS)
        val sharedLine = lines.firstOrNull { it.startsWith("shared workspace:") }
        val ok = "termux ok" in lines && found != null && found.values.all { it != null } && sharedLine == SHARED_OK
        val text = buildString {
            if (found != null) append(ToolCheck.report(found)) else append(out.trim().ifEmpty { "exit ${r.exitCode}" })
            if (found != null && sharedLine != null) append('\n').append(sharedLine)
        }
        return Result(ok = ok, text = text, ran = true)
    }

    /**
     * Runs the automatic check once per visit of the page: [claim] is true the first time it is
     * asked while Termux is ready, and false after that (and while not ready), however often the
     * page recomposes. A new visit makes a new instance.
     */
    class OncePerEntry {
        private var done = false

        @Synchronized
        fun claim(ready: Boolean): Boolean {
            if (done || !ready) return false
            done = true
            return true
        }
    }
}
