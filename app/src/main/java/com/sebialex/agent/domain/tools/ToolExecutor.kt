package com.sebialex.agent.domain.tools

import com.sebialex.agent.data.shell.SandboxShell
import com.sebialex.agent.data.shell.Shell
import com.sebialex.agent.data.web.WebGateway
import com.sebialex.agent.data.web.hostOf
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.markdown.LineDiff
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Executes tool calls against the workspace, sandbox shell and web gateway. */
class ToolExecutor(
    private val workspace: Workspace,
    private val shell: Shell,
    private val web: WebGateway,
) {
    private var cwd = "/"

    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (call) {
                is ToolCall.WebSearch -> {
                    val hits = web.search(call.query)
                    ToolResult(
                        call.callId, true,
                        hits.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" }.ifEmpty { "no results" },
                        searchHits = hits,
                    )
                }
                is ToolCall.WebFetch -> {
                    val page = web.fetch(call.url)
                    val text = page.text.ifBlank { "(the page had no readable text)" }
                    // A page only counts as a source when it actually returned readable content.
                    val sources = if (page.text.isNotBlank()) listOf(SourceRef(page.url, hostOf(page.url), page.title)) else emptyList()
                    ToolResult(call.callId, true, text, sources = sources)
                }
                is ToolCall.Shell -> {
                    val r = shell.execute(call.command, cwd)
                    cwd = r.cwd
                    ToolResult(call.callId, r.exitCode == 0, r.output, exitCode = r.exitCode)
                }
                is ToolCall.ReadFile -> {
                    // Imported photos and other binaries would only reach the model as noise.
                    val f = workspace.resolve(call.path)
                    if (f.isFile && isBinary(f)) {
                        ToolResult(call.callId, true, "binary file, ${f.length()} bytes (${call.path}). Use the python tool to inspect it.")
                    } else ToolResult(call.callId, true, workspace.read(call.path))
                }
                is ToolCall.WriteFile -> {
                    val w = workspace.write(call.path, call.content)
                    val (added, removed) = if (w.previous != null) LineDiff.counts(w.previous, call.content)
                    else call.content.trimEnd('\n').count { it == '\n' } + 1 to 0
                    val attachment = FileAttachment(
                        path = w.virtualPath,
                        name = w.virtualPath.substringAfterLast('/'),
                        baseSnapshot = w.snapshotId,
                        added = added,
                        removed = removed,
                        updatedAt = System.currentTimeMillis(),
                    )
                    ToolResult(call.callId, true, "wrote ${call.content.length} bytes to ${w.virtualPath}", attachment = attachment)
                }
                is ToolCall.Python -> runPython(call)
                is ToolCall.Invalid -> ToolResult(call.callId, false, call.reason)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(call.callId, false, e.message ?: e.javaClass.simpleName, exitCode = if (call is ToolCall.Shell || call is ToolCall.Python) 1 else null)
        }
    }

    private fun isBinary(f: java.io.File): Boolean = f.inputStream().use { input ->
        val buf = ByteArray(8192)
        val n = input.read(buf)
        n > 0 && (0 until n).any { buf[it] == 0.toByte() }
    }

    /**
     * Saves the code as a script in the workspace, pip-installs the requested packages, then runs
     * it with Termux's Python from the shell's current directory. A failed install is a failed
     * step with pip's real output; the script is not run in that case.
     */
    private fun runPython(call: ToolCall.Python): ToolResult {
        val script = workspace.write(call.scriptPath, call.code).virtualPath
        val out = StringBuilder()
        if (call.packages.isNotEmpty()) {
            val pkgs = call.packages.joinToString(" ") { SandboxShell.shellQuote(it) }
            val install = shell.execute("python -m pip install $pkgs", cwd)
            out.append("$ pip install ").append(call.packages.joinToString(" ")).append('\n')
            if (install.output.isNotEmpty()) out.append(install.output).append('\n')
            if (install.exitCode != 0) {
                return ToolResult(call.callId, false, out.toString().trimEnd(), exitCode = install.exitCode)
            }
            out.append('\n')
        }
        val r = shell.execute("python ${SandboxShell.shellQuote("/workspace$script")}", cwd)
        if (call.packages.isNotEmpty()) out.append("$ python ").append(call.scriptName).append('\n')
        out.append(r.output)
        return ToolResult(call.callId, r.exitCode == 0, out.toString().trimEnd(), exitCode = r.exitCode)
    }
}
