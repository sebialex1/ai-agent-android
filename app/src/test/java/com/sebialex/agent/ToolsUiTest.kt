package com.sebialex.agent

import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.shell.TermuxProblem
import com.sebialex.agent.data.shell.TermuxResult
import com.sebialex.agent.data.shell.TermuxRunner
import com.sebialex.agent.data.shell.TermuxSetupCheck
import com.sebialex.agent.data.shell.TermuxStatus
import com.sebialex.agent.data.shell.WorkspaceMirror
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.TodoItem
import com.sebialex.agent.ui.chat.TodoStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The todo section of the chatbox and the Termux setup check of Settings. */
class ToolsUiTest {

    // --- Todo list as part of the chatbox ---

    @Test fun todoSectionOnlyExistsWithEntries() {
        assertFalse(TodoStrip.visible(emptyList()))
        assertTrue(TodoStrip.visible(listOf(TodoItem(1, "a"))))
    }

    @Test fun todoSectionTexts() {
        val items = listOf(TodoItem(1, "Fetch the data", done = true), TodoItem(2, "Write the introduction and then the final section"), TodoItem(3, "c"))
        assertEquals("1/3", TodoStrip.count(items))
        assertEquals("Todo \u00b7 1 of 3 done", TodoStrip.title(items))
        val hint = TodoStrip.hint(items, fit = 27)
        assertTrue(hint, hint.startsWith("Next: Write"))
        assertTrue(hint, hint.endsWith("section"))
        assertTrue(hint.length <= "Next: ".length + 21)
        assertFalse(TodoStrip.allDone(items))
        val done = items.map { it.copy(done = true) }
        assertEquals("All 3 done", TodoStrip.hint(done, fit = 40))
        assertTrue(TodoStrip.allDone(done))
        assertFalse(TodoStrip.allDone(emptyList()))
    }

    // --- Termux setup check: same gate and probe as a real command ---

    /** Answers the setup check and the shell's tool probe like Termux would. */
    private class FakeTermux(var status: TermuxStatus, val installed: Set<String>, val sharedWritable: Boolean = true) : TermuxRunner {
        val runs = mutableListOf<String>()
        override fun status() = status
        override fun run(script: String, workdir: String, timeoutSeconds: Long, label: String): TermuxResult {
            runs += script
            val probe = script.substringAfter("for t in ", "").substringBefore("; do")
            val names = Regex("'([^']+)'").findAll(probe).map { it.groupValues[1] }.toList()
            val out = buildList {
                if (script.startsWith("echo 'termux ok'")) add("termux ok")
                names.forEach { add(if (it in installed) "ok:$it:/data/data/com.termux/files/usr/bin/$it" else "missing:$it") }
                if ("shared workspace" in script) add(if (sharedWritable) "shared workspace: ok" else "shared workspace: not writable from Termux (run termux-setup-storage in Termux)")
            }.joinToString("\n")
            return TermuxResult(out, "", 0, -1, "", false)
        }
    }

    private val ready = TermuxStatus(installed = true, runPermission = true, storage = true)
    private val shared = Files.createTempDirectory("shared").toFile()

    @Test fun setupCheckPassesWhenEverythingIsThere() {
        val t = FakeTermux(ready, TermuxSetupCheck.PROGRAMS.toSet())
        val r = TermuxSetupCheck.run(t, shared)
        assertTrue(r.text, r.ok)
        assertTrue(r.ran)
        assertTrue(r.text, "python: installed" in r.text)
        assertTrue(r.text, "shared workspace: ok" in r.text)
        assertEquals(1, t.runs.size)
    }

    @Test fun setupCheckReportsMissingProgramsAndUnwritableStorage() {
        val noPython = TermuxSetupCheck.run(FakeTermux(ready, setOf("bash", "apt", "dpkg", "pkg")), shared)
        assertFalse(noPython.ok)
        assertTrue(noPython.text, "python: missing" in noPython.text && "pkg install -y python" in noPython.text)
        val noStorage = TermuxSetupCheck.run(FakeTermux(ready, TermuxSetupCheck.PROGRAMS.toSet(), sharedWritable = false), shared)
        assertFalse(noStorage.ok)
        assertTrue(noStorage.text, "not writable" in noStorage.text)
    }

    @Test fun setupCheckNeverReachesTermuxWhenCommandsWouldNot() {
        // Same gate as SandboxShell: without the run permission (or storage) nothing goes to Termux.
        val t = FakeTermux(ready.copy(runPermission = false), TermuxSetupCheck.PROGRAMS.toSet())
        val r = TermuxSetupCheck.run(t, shared)
        assertFalse(r.ok)
        assertFalse(r.ran)
        assertEquals(TermuxProblem.NO_RUN_PERMISSION.message, r.text)
        assertTrue(t.runs.isEmpty())
    }

    @Test fun setupCheckAgreesWithTheShellsOwnLookup() {
        val t = FakeTermux(ready, setOf("bash", "apt", "dpkg", "pkg", "python"))
        val base = Files.createTempDirectory("ws").toFile()
        val ws = Workspace(base)
        val shell = SandboxShell(ws, t, WorkspaceMirror(ws.root, File(base, "shared/workspace"), File(base, "mirror.manifest")))
        val lookup = shell.which(TermuxSetupCheck.PROGRAMS)!!
        val check = TermuxSetupCheck.run(t, shared)
        assertFalse(check.ok)
        for (p in TermuxSetupCheck.PROGRAMS) {
            val installedPerShell = lookup[p] != null
            assertTrue("$p: ${check.text}", ("$p: installed" in check.text) == installedPerShell)
        }
    }

    @Test fun theAutomaticCheckRunsOncePerEntry() {
        val entry = TermuxSetupCheck.OncePerEntry()
        // Not ready on entry: no check yet (recompositions change nothing).
        assertFalse(entry.claim(ready = false))
        assertFalse(entry.claim(ready = false))
        // Becomes ready during the same visit: exactly one check.
        assertTrue(entry.claim(ready = true))
        repeat(5) { assertFalse(entry.claim(ready = true)) }
        // A new visit of the page is a new entry and checks again.
        assertTrue(TermuxSetupCheck.OncePerEntry().claim(ready = true))
    }
}
