package com.sebialex.agent.domain.tools

import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.data.workspace.WorkspaceException
import com.sebialex.agent.domain.markdown.LineDiff
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import java.io.File

/** Workspace file tools beyond read/write: list, search and exact-text edit. */
internal object FileTools {
    private const val MAX_LIST = 400
    private const val MAX_MATCHES = 200
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    fun list(ws: Workspace, call: ToolCall.ListFiles): ToolResult {
        val root = ws.resolve(call.path)
        if (!root.exists()) throw WorkspaceException("${call.path}: no such file or directory")
        val files = if (call.recursive) root.walkTopDown().filter { it != root }.toList()
        else ws.list(call.path)
        val visible = files.filterNot { f -> f.relativeTo(root).path.split(File.separatorChar).any { it.startsWith(".") } }
        if (visible.isEmpty()) return ToolResult(call.callId, true, "(empty) ${ws.virtualOf(root)}")
        val lines = visible.take(MAX_LIST).map { f ->
            val p = ws.virtualOf(f)
            if (f.isDirectory) "$p/" else "$p  ${size(f.length())}"
        }
        val more = if (visible.size > MAX_LIST) "\n[${visible.size - MAX_LIST} more not shown]" else ""
        return ToolResult(call.callId, true, lines.joinToString("\n") + more)
    }

    fun search(ws: Workspace, call: ToolCall.SearchFiles): ToolResult {
        val root = ws.resolve(call.path)
        val pattern = if (call.regex) Regex(call.query, RegexOption.IGNORE_CASE) else null
        val out = StringBuilder()
        var matches = 0
        val candidates = if (root.isFile) sequenceOf(root) else root.walkTopDown().filter { it.isFile }
        for (f in candidates) {
            if (ws.virtualOf(f).split('/').any { it.startsWith(".") }) continue
            if (f.length() > MAX_FILE_BYTES || isBinary(f)) continue
            f.useLines { lines ->
                lines.forEachIndexed { i, line ->
                    if (matches >= MAX_MATCHES) return@useLines
                    val hit = pattern?.containsMatchIn(line) ?: line.contains(call.query, ignoreCase = true)
                    if (hit) {
                        out.append(ws.virtualOf(f)).append(':').append(i + 1).append(": ").append(line.trim().take(240)).append('\n')
                        matches++
                    }
                }
            }
            if (matches >= MAX_MATCHES) break
        }
        if (matches == 0) return ToolResult(call.callId, true, "no matches for \"${call.query}\"")
        val cap = if (matches >= MAX_MATCHES) "[stopped after $MAX_MATCHES matches]" else "$matches match" + if (matches == 1) "" else "es"
        return ToolResult(call.callId, true, out.toString() + cap)
    }

    fun edit(ws: Workspace, call: ToolCall.EditFile): ToolResult {
        val before = ws.read(call.path)
        if (call.oldText.isEmpty()) return ToolResult(call.callId, false, "old_text is empty; use write_file to create or replace a whole file.")
        var count = 0
        var at = before.indexOf(call.oldText)
        while (at >= 0) {
            count++
            at = before.indexOf(call.oldText, at + call.oldText.length)
        }
        when {
            count == 0 -> return ToolResult(call.callId, false, "old_text was not found in ${call.path}. Read the file and copy the exact text, including whitespace.")
            count > 1 && !call.replaceAll -> return ToolResult(call.callId, false, "old_text matches $count times in ${call.path}. Add more context to make it unique, or set replace_all.")
        }
        val after = if (call.replaceAll) before.replace(call.oldText, call.newText) else before.replaceFirst(call.oldText, call.newText)
        val w = ws.write(call.path, after)
        val (added, removed) = LineDiff.counts(before, after)
        val attachment = FileAttachment(
            path = w.virtualPath,
            name = w.virtualPath.substringAfterLast('/'),
            baseSnapshot = w.snapshotId,
            added = added,
            removed = removed,
            updatedAt = System.currentTimeMillis(),
        )
        val n = if (call.replaceAll) count else 1
        return ToolResult(call.callId, true, "edited ${w.virtualPath}: $n replacement" + (if (n == 1) "" else "s") + " (+$added \u2212$removed lines)", attachment = attachment)
    }

    private fun size(b: Long) = when {
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.1f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    fun isBinary(f: File): Boolean = f.inputStream().use { input ->
        val buf = ByteArray(8192)
        val n = input.read(buf)
        n > 0 && (0 until n).any { buf[it] == 0.toByte() }
    }
}
