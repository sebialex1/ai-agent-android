package com.sebialex.agent.domain.tools

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
                is ToolCall.ReadFile -> ToolResult(call.callId, true, workspace.read(call.path))
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
                is ToolCall.Invalid -> ToolResult(call.callId, false, call.reason)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(call.callId, false, e.message ?: e.javaClass.simpleName, exitCode = if (call is ToolCall.Shell) 1 else null)
        }
    }
}
