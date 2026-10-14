package com.sebialex.agent.data.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Who ran a command in the shared terminal. */
enum class TerminalOrigin { AGENT, USER }

/** One command in the shared terminal. [exitCode] is null while it is still [running]. */
data class TerminalEntry(
    val id: Long,
    val origin: TerminalOrigin,
    val cwd: String,
    val command: String,
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = false,
)

/**
 * The one sandbox terminal of a conversation. The agent's shell and python tools and the user's
 * terminal tab all run through it, so they share the same [Shell] (workspace, Termux mirror),
 * the same working directory and one history: commands the agent ran appear here with their
 * output, and a `cd` by either side is seen by the other. Commands run one at a time, in the
 * order they arrive. Note that each command is its own bash process (as for the agent), so
 * exported variables do not outlive the command; the working directory does.
 */
class SharedTerminal(private val shell: Shell) {
    private val lock = ReentrantLock()
    private var nextId = 1L
    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries: StateFlow<List<TerminalEntry>> = _entries.asStateFlow()
    private val _cwd = MutableStateFlow("/")
    /** Current directory, relative to the workspace root ("/" is the root). */
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    /** Runs [command] in the shared working directory and records it. Blocking: call off the main thread. */
    fun run(origin: TerminalOrigin, command: String): ShellResult {
        val id = synchronized(this) { nextId++ }
        // Show the command immediately, even while it waits for an earlier one to finish.
        val dir = _cwd.value
        add(TerminalEntry(id, origin, dir, command, running = true))
        return lock.withLock {
            val at = _cwd.value
            val r = try {
                shell.execute(command, at)
            } catch (e: Exception) {
                ShellResult("${e.javaClass.simpleName}: ${e.message}", 1, at)
            }
            if (r.output == CLEAR) {
                _entries.value = _entries.value.filter { it.running && it.id != id }
                _cwd.value = r.cwd
            } else {
                _cwd.value = r.cwd
                update(id) { it.copy(cwd = at, output = r.output.take(MAX_OUTPUT), exitCode = r.exitCode, running = false) }
            }
            r
        }
    }

    /** Records a command that was not run (blocked, or a missing program), without touching the shell. */
    fun note(origin: TerminalOrigin, command: String, output: String, exitCode: Int) {
        val id = synchronized(this) { nextId++ }
        add(TerminalEntry(id, origin, _cwd.value, command, output.take(MAX_OUTPUT), exitCode, running = false))
    }

    private fun add(e: TerminalEntry) {
        _entries.value = (_entries.value + e).takeLast(MAX_ENTRIES)
    }

    private fun update(id: Long, f: (TerminalEntry) -> TerminalEntry) {
        _entries.value = _entries.value.map { if (it.id == id) f(it) else it }
    }

    companion object {
        /** `clear` in the sandbox shell answers with a form feed. */
        const val CLEAR = "\u000c"
        const val MAX_ENTRIES = 300
        const val MAX_OUTPUT = 20_000
    }
}
