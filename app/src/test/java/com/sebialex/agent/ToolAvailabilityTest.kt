package com.sebialex.agent

import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.shell.TermuxResult
import com.sebialex.agent.data.shell.TermuxRunner
import com.sebialex.agent.data.shell.TermuxStatus
import com.sebialex.agent.data.shell.ToolCheck
import com.sebialex.agent.data.shell.WorkspaceMirror
import com.sebialex.agent.data.web.FetchedPage
import com.sebialex.agent.data.web.WebGateway
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.SearchHit
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The "yt-dlp is not installed" loop: the pre-run availability check and the real execution
 * must look at the same program names and the same environment.
 */
class ToolAvailabilityTest {

    /** A Termux that has [installed] on its PATH and answers probes and commands like the real one. */
    private class FakeTermux(var status: TermuxStatus, val installed: Set<String>) : TermuxRunner {
        val probes = mutableListOf<String>()
        val commands = mutableListOf<String>()
        override fun status() = status
        override fun run(script: String, workdir: String, timeoutSeconds: Long, label: String): TermuxResult {
            if (script.startsWith("for t in ")) {
                probes += script
                val names = Regex("'([^']+)'").findAll(script.substringBefore("; do")).map { it.groupValues[1] }.toList()
                val out = names.joinToString("\n") { if (it in installed) "ok:$it:/data/data/com.termux/files/usr/bin/$it" else "missing:$it" }
                return TermuxResult(out, "", 0, -1, "", false)
            }
            commands += script
            val program = script.trim().substringBefore(' ')
            return if (program in installed) TermuxResult("ran $program", "", 0, -1, "", false)
            else TermuxResult("", "bash: $program: command not found", 127, -1, "", false)
        }
    }

    private val ready = TermuxStatus(installed = true, runPermission = true, storage = true)
    private val noStorage = TermuxStatus(installed = true, runPermission = true, storage = false)

    private val noWeb = object : WebGateway {
        override suspend fun search(query: String): List<SearchHit> = emptyList()
        override suspend fun fetch(url: String) = FetchedPage(url, "", "")
    }

    private fun shell(termux: FakeTermux): Pair<SandboxShell, Workspace> {
        val base = Files.createTempDirectory("tools").toFile()
        val ws = Workspace(base)
        val mirror = WorkspaceMirror(ws.root, File(base, "shared/workspace"), File(base, "mirror.manifest"))
        return SandboxShell(ws, termux, mirror) to ws
    }

    @Test
    fun redirectionsAndContinuationsAreNotProgramNames() {
        val builtins = setOf("tail", "head", "grep", "cat", "echo")
        // Before: [yt-dlp, 1] — the "1" of 2>&1 was looked up and reported as not installed.
        assertEquals(listOf("yt-dlp"), ToolCheck.programs("yt-dlp -o \"downloads/%(title)s.%(ext)s\" \"https://x.y/v\" 2>&1 | tail -n 20", builtins))
        assertEquals(listOf("ffmpeg"), ToolCheck.programs("ffmpeg -i a.mp4 b.mp3 >&2", builtins))
        assertEquals(listOf("yt-dlp"), ToolCheck.programs("yt-dlp URL &>log.txt", builtins))
        assertEquals(listOf("yt-dlp", "sort"), ToolCheck.programs("yt-dlp -F URL |& sort", builtins))
        assertEquals(listOf("ffmpeg"), ToolCheck.programs("ffmpeg -i in.mp4 \\\n  out.mp4", builtins))
        assertEquals(listOf("find"), ToolCheck.programs("find . -name '*.part' -exec rm {} \\;", builtins))
        // Real separators still split.
        assertEquals(listOf("yt-dlp", "ffmpeg"), ToolCheck.programs("yt-dlp URL && ffmpeg -i a b; ls", builtins + "ls"))
        assertEquals(listOf("yt-dlp", "aria2c"), ToolCheck.programs("yt-dlp URL & aria2c URL", builtins))
    }

    @Test
    fun aCommandThatSetsPathIsNotPreChecked() {
        assertTrue(ToolCheck.changesPath("PATH=\$HOME/.local/bin:\$PATH yt-dlp URL"))
        assertTrue(ToolCheck.changesPath("export PATH=\$PATH:~/bin; yt-dlp URL"))
        assertFalse(ToolCheck.changesPath("yt-dlp URL"))
        assertFalse(ToolCheck.changesPath("echo \$PATH"))
    }

    @Test
    fun ytDlpWithStderrRedirectRunsInsteadOfNotInstalled() = runBlocking {
        val termux = FakeTermux(ready, installed = setOf("yt-dlp"))
        val (shell, ws) = shell(termux)
        val tools = ToolExecutor(ws, shell, web = noWeb)
        val r = tools.execute(ToolCall.Shell("1", "yt-dlp -o out.mp4 \"https://example.com/v\" 2>&1 | tail -n 5"))
        assertFalse(r.output, "not installed" in r.output)
        assertEquals(0, r.exitCode)
        assertEquals(1, termux.commands.size)
    }

    @Test
    fun checkAndRunUseTheSameEnvironment() = runBlocking {
        val termux = FakeTermux(ready, installed = setOf("yt-dlp"))
        val (shell, ws) = shell(termux)
        val tools = ToolExecutor(ws, shell, web = noWeb)
        val check = tools.execute(ToolCall.CheckTools("1", listOf("yt-dlp")))
        assertTrue(check.output, "yt-dlp: installed" in check.output)
        val run = tools.execute(ToolCall.Shell("2", "yt-dlp --version"))
        assertEquals(0, run.exitCode)
        // The lookup and the command both went to Termux, none to the device shell.
        assertEquals(1, termux.probes.size)
        assertEquals(listOf("yt-dlp --version"), termux.commands)
    }

    @Test
    fun aPathFoundInTermuxNeverVouchesForTheDeviceShell() {
        val program = "agent-test-only-tool"
        val termux = FakeTermux(ready, installed = setOf(program))
        val (shell, _) = shell(termux)
        assertEquals("/data/data/com.termux/files/usr/bin/$program", shell.which(listOf(program))!![program])
        // Termux becomes unreachable: the cached Termux path must not claim the program is there.
        termux.status = noStorage
        assertNull(shell.which(listOf(program)))
        val r = shell.execute("$program --version")
        assertNotEquals(0, r.exitCode)
        if (r.exitCode == 127) assertTrue(r.output, "Termux environment is not available" in r.output)
        // Back in Termux it is looked up again, where it runs.
        termux.status = ready
        assertEquals("/data/data/com.termux/files/usr/bin/$program", shell.which(listOf(program))!![program])
        assertEquals(2, termux.probes.size)
    }

    @Test
    fun termuxUnreachableIsNotReportedAsNotInstalled() = runBlocking {
        val termux = FakeTermux(noStorage, installed = setOf("yt-dlp"))
        val (shell, ws) = shell(termux)
        val tools = ToolExecutor(ws, shell, web = noWeb)
        val r = tools.execute(ToolCall.Shell("1", "yt-dlp --version"))
        assertFalse(r.output, "is not installed" in r.output)
        assertTrue(termux.probes.isEmpty())
    }
}
