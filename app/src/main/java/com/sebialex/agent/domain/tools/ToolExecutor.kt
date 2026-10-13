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
    /** Earlier conversations, read-only. Null when this runtime has no access to them. */
    private val memory: ConversationMemory? = null,
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
                    val sources = if (page.text.isNotBlank()) listOf(SourceRef(page.url, hostOf(page.url), page.title, snippetOf(page.text))) else emptyList()
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
                is ToolCall.Memory -> runMemory(call)
                is ToolCall.ListFiles -> FileTools.list(workspace, call)
                is ToolCall.SearchFiles -> FileTools.search(workspace, call)
                is ToolCall.EditFile -> FileTools.edit(workspace, call)
                is ToolCall.MoveFile -> {
                    workspace.move(call.from, call.to)
                    ToolResult(call.callId, true, "moved ${workspace.toVirtual(call.from)} to ${workspace.toVirtual(call.to)}")
                }
                is ToolCall.DeleteFile -> {
                    workspace.delete(call.path, recursive = true)
                    ToolResult(call.callId, true, "deleted ${workspace.toVirtual(call.path)}")
                }
                is ToolCall.DownloadFile -> NetTools.download(workspace, call)
                is ToolCall.HttpRequest -> NetTools.request(call)
                is ToolCall.DeviceInfo -> ToolResult(call.callId, true, NetTools.deviceInfo())
                is ToolCall.ViewImage -> viewImage(call)
                is ToolCall.Invalid -> ToolResult(call.callId, false, call.reason)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(call.callId, false, e.message ?: e.javaClass.simpleName, exitCode = if (call is ToolCall.Shell || call is ToolCall.Python) 1 else null)
        }
    }

    /** Loads a workspace image (or video frames) so the brain can show it to the model. */
    private fun viewImage(call: ToolCall.ViewImage): ToolResult {
        val f = workspace.resolve(call.path)
        if (!f.isFile) return ToolResult(call.callId, false, "${call.path}: no such file")
        val label = workspace.virtualOf(f)
        val images = com.sebialex.agent.data.workspace.ImageEncoder.load(f, label)
        if (images.isEmpty()) return ToolResult(call.callId, false, "$label is not an image or video that can be shown.")
        val what = if (images.size == 1) "The image $label" else "${images.size} frames of $label"
        return ToolResult(call.callId, true, "$what follows in the next message.", images = images)
    }

    private fun runMemory(call: ToolCall.Memory): ToolResult {
        val m = memory ?: return ToolResult(call.callId, false, "Conversation memory is not available.")
        val id = call.conversationId?.trim().orEmpty()
        return when (call.action) {
            "list_conversations" -> ToolResult(call.callId, true, m.list(call.query))
            "summarize_conversation" -> if (id.isEmpty()) ToolResult(call.callId, false, "summarize_conversation needs conversation_id.")
                else ToolResult(call.callId, true, m.summarize(id))
            "read_conversation" -> if (id.isEmpty()) ToolResult(call.callId, false, "read_conversation needs conversation_id.")
                else ToolResult(call.callId, true, m.read(id, call.offset))
            else -> ToolResult(call.callId, false, "Unknown action \"${call.action}\". Use one of: ${com.sebialex.agent.domain.model.MEMORY_ACTIONS.joinToString(", ")}.")
        }
    }

    /** A short, single-paragraph excerpt of a page for the source preview card. */
    private fun snippetOf(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= SNIPPET_CHARS) return flat
        val cut = flat.take(SNIPPET_CHARS)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', '-') + "\u2026"
    }

    private companion object {
        const val SNIPPET_CHARS = 280
        const val MISSING_PYTHON = "Python is not installed in Termux. Run `pkg install -y python` with the shell tool, then retry."
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
            if (install.exitCode == 127) out.append(MISSING_PYTHON).append('\n')
            if (install.exitCode != 0) {
                return ToolResult(call.callId, false, out.toString().trimEnd(), exitCode = install.exitCode)
            }
            out.append('\n')
        }
        val r = shell.execute("python ${SandboxShell.shellQuote("/workspace$script")}", cwd)
        if (call.packages.isNotEmpty()) out.append("$ python ").append(call.scriptName).append('\n')
        out.append(r.output)
        // 127 from Termux's bash = command not found: Python itself is not installed yet.
        if (r.exitCode == 127 && "not found" in r.output) out.append('\n').append(MISSING_PYTHON)
        return ToolResult(call.callId, r.exitCode == 0, out.toString().trimEnd(), exitCode = r.exitCode)
    }
}
