package com.sebialex.agent

import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.shell.ShellResult
import com.sebialex.agent.data.shell.ShizukuGate
import com.sebialex.agent.data.shell.WorkspaceMirror
import com.sebialex.agent.data.workspace.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Where Shizuku sits in the shell's routing: on top of Termux and the device shell for ordinary
 * external commands when the user opted in and the gate is ready, never for Termux-only programs,
 * always with the workspace mirrored and /workspace rewritten to the shared path.
 */
class ShizukuShellTest {

    private class FakeGate(var ok: Boolean = true) : ShizukuGate {
        val commands = mutableListOf<String>()
        val cwds = mutableListOf<String>()
        override fun ready() = ok
        override fun run(command: String, cwd: String, timeoutSeconds: Long): ShellResult {
            commands += command
            cwds += cwd
            return ShellResult("via-shizuku", 0, cwd)
        }
    }

    private fun shell(shizuku: FakeGate?): Pair<SandboxShell, File> {
        val base = Files.createTempDirectory("shizuku").toFile()
        val ws = Workspace(base)
        val mirror = WorkspaceMirror(ws.root, File(base, "shared/workspace"), File(base, "mirror.manifest"))
        return SandboxShell(ws, mirror = mirror, shizuku = shizuku) to base
    }

    @Test
    fun aReadyGateRunsExternalCommands() {
        val gate = FakeGate()
        val (sh, _) = shell(gate)
        val r = sh.execute("uptime")
        assertEquals("via-shizuku", r.output)
        assertEquals(0, r.exitCode)
        assertEquals(listOf("uptime"), gate.commands)
    }

    @Test
    fun workspacePathsAreRewrittenToTheSharedMirror() {
        val gate = FakeGate()
        val (sh, base) = shell(gate)
        sh.execute("stat /workspace/notes.txt")
        val script = gate.commands.single()
        // The Shizuku shell never sees /workspace as the workspace root: it lives in the mirror.
        assertFalse("stat /workspace" in script)
        assertTrue("shared/workspace" in script)
    }

    @Test
    fun aGateThatIsNotReadyChangesNothing() {
        val gate = FakeGate(ok = false)
        val (sh, _) = shell(gate)
        val r = sh.execute("uptime")
        assertTrue(gate.commands.isEmpty())
        // The device shell answered (or said why not) \u2014 anything but the Shizuku result.
        assertTrue(r.output != "via-shizuku")
    }

    @Test
    fun termuxProgramsNeverRunThroughShizuku() {
        // Without a working Termux, python is refused with the Termux reason (exit 127), not
        // silently run where it does not exist.
        val gate = FakeGate()
        val (sh, _) = shell(gate)
        val r = sh.execute("python -V")
        assertTrue(gate.commands.isEmpty())
        assertEquals(127, r.exitCode)
        assertTrue("Termux" in r.output)
    }

    @Test
    fun disabledByDefaultKeepsTheOldBehaviour() {
        val (sh, _) = shell(shizuku = null)
        val r = sh.execute("uptime")
        // No Shizuku gate: the device shell path, same as before this feature.
        assertTrue(r.output != "via-shizuku")
    }
}
