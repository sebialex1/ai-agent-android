package com.sebialex.agent.data.persistence

import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import com.sebialex.agent.domain.tools.ConversationMemory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [ConversationMemory] over the saved conversations in [store], the same `session.json` files the
 * chat and the conversations menu use. It only reads: there is no code path that writes a
 * session. The conversation the agent is running in ([currentId]) is excluded, since it is
 * already in the model's context.
 *
 * Budgets (characters, roughly 4 per token): a list is at most ~5 KB, a summary ~2.5 KB and one
 * page of a conversation ~7 KB, so even small on-device context windows can use it. Summaries are
 * built from the stored messages without another model call: fast, free and deterministic.
 */
class ConversationMemoryReader(
    private val store: SessionStore,
    private val currentId: String,
) : ConversationMemory {

    override fun list(query: String?): String {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val all = store.list().filter { it.id != currentId }
        if (all.isEmpty()) return "No earlier conversations are saved."
        val sessions = if (q == null) all.take(LIST_LIMIT).mapNotNull { store.load(it.id) }
        else all.asSequence().mapNotNull { store.load(it.id) }.filter { matches(it, q) }.take(LIST_LIMIT).toList()
        if (sessions.isEmpty()) return "No earlier conversation mentions \"$q\"."
        val out = StringBuilder()
        out.append(if (q == null) "Most recent conversations (newest first, ${sessions.size} of ${all.size}):\n" else "Conversations mentioning \"$q\":\n")
        for (s in sessions) {
            val firstAsk = s.turns.firstOrNull { it.role == Role.USER }?.text.orEmpty()
            val line = "- id: ${s.id} | ${s.title.ifBlank { "Untitled" }} | ${date(s.updatedAt)} | ${s.turns.size} messages | asked: ${oneLine(firstAsk, 110)}\n"
            if (out.length + line.length > LIST_CHARS) {
                out.append("[more conversations not shown; use a query to narrow down]\n")
                break
            }
            out.append(line)
        }
        out.append("Use summarize_conversation with an id for details, read_conversation to see the full messages.")
        return out.toString()
    }

    override fun summarize(id: String): String {
        val s = load(id) ?: return missing(id)
        val users = s.turns.filter { it.role == Role.USER && it.text.isNotBlank() }
        val answers = s.turns.filter { it.role == Role.ASSISTANT && it.complete && it.text.isNotBlank() }
        val steps = s.turns.flatMap { it.steps }.filter { it.kind != StepKind.REASONING && it.kind != StepKind.PERMISSION }
        val out = StringBuilder()
        out.append("Conversation ${s.id}: ${s.title.ifBlank { "Untitled" }}\n")
        out.append("Started ${date(s.createdAt)}, last message ${date(s.updatedAt)}, ${s.turns.size} messages.\n")
        if (users.isNotEmpty()) {
            out.append("\nUser requests:\n")
            // First and last requests carry the most context when there are many.
            val picked = if (users.size <= MAX_REQUESTS) users else users.take(MAX_REQUESTS / 2) + users.takeLast(MAX_REQUESTS / 2)
            picked.forEachIndexed { i, t ->
                if (users.size > MAX_REQUESTS && i == MAX_REQUESTS / 2) out.append("- [${users.size - MAX_REQUESTS} more requests]\n")
                out.append("- ").append(oneLine(t.text, 160)).append('\n')
            }
        }
        answers.lastOrNull()?.let { out.append("\nLast answer (excerpt):\n").append(clip(it.text, 600)).append('\n') }
        if (steps.isNotEmpty()) {
            val counts = steps.groupingBy { it.tool }.eachCount().entries.sortedByDescending { it.value }
            out.append("\nTools used: ").append(counts.joinToString(", ") { "${it.key} x${it.value}" })
            val failed = steps.count { it.status == StepStatus.FAILED }
            if (failed > 0) out.append(" ($failed failed)")
            out.append('\n')
        }
        val files = s.turns.flatMap { it.attachments }.map { it.path }.distinct()
        if (files.isNotEmpty()) out.append("Files written: ").append(files.take(20).joinToString(", ")).append(if (files.size > 20) ", \u2026" else "").append('\n')
        val sources = s.turns.flatMap { it.sources }.distinctBy { it.url }
        if (sources.isNotEmpty()) out.append("Sources read: ").append(sources.take(8).joinToString(", ") { it.title.ifBlank { it.host } }).append('\n')
        out.append("\nread_conversation with offset 0 shows the full messages.")
        return clip(out.toString(), SUMMARY_CHARS)
    }

    override fun read(id: String, offset: Int): String {
        val s = load(id) ?: return missing(id)
        if (s.turns.isEmpty()) return "Conversation $id has no messages."
        val start = offset.coerceIn(0, s.turns.size)
        if (start >= s.turns.size) return "Conversation $id has ${s.turns.size} messages; offset $offset is past the end."
        val out = StringBuilder("Conversation ${s.id}: ${s.title.ifBlank { "Untitled" }} (messages ${start + 1}-")
        val body = StringBuilder()
        var next = start
        while (next < s.turns.size) {
            val block = render(next, s.turns[next])
            // Always include at least one message, even if it is long.
            if (next > start && body.length + block.length > PAGE_CHARS) break
            body.append(block)
            next++
        }
        out.append("$next of ${s.turns.size})\n\n").append(body)
        out.append(if (next < s.turns.size) "\n[more: call read_conversation with offset $next]" else "\n[end of conversation]")
        return out.toString()
    }

    private fun render(index: Int, t: Turn): String = buildString {
        append("#").append(index).append(' ')
        append(if (t.role == Role.USER) "User" else "Assistant").append(" (").append(date(t.createdAt)).append("):\n")
        append(clip(t.text.ifBlank { if (t.complete) "(empty)" else "(unfinished)" }, TURN_CHARS)).append('\n')
        val actions = t.steps.filter { it.kind != StepKind.REASONING && it.kind != StepKind.PERMISSION }
        if (actions.isNotEmpty()) {
            append("Actions: ")
            append(actions.take(10).joinToString("; ") { "${it.tool} ${oneLine(it.payload, 80)}" + if (it.status == StepStatus.FAILED) " (failed)" else "" })
            if (actions.size > 10) append("; \u2026")
            append('\n')
        }
        if (t.attachments.isNotEmpty()) append("Files: ").append(t.attachments.joinToString(", ") { it.path }).append('\n')
        append('\n')
    }

    private fun load(id: String): Session? {
        val clean = id.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains("..")) return null
        if (clean == currentId) return null
        return runCatching { store.load(clean) }.getOrNull()
    }

    private fun missing(id: String) =
        if (id.trim() == currentId) "That is the current conversation; it is already in your context."
        else "No saved conversation has id \"$id\". Use list_conversations to see the ids."

    private fun matches(s: Session, q: String): Boolean =
        s.title.contains(q, ignoreCase = true) || s.turns.any { it.text.contains(q, ignoreCase = true) }

    private fun oneLine(text: String, max: Int): String = clip(text.replace(Regex("\\s+"), " ").trim(), max)

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max).trimEnd() + "\u2026"

    private fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private companion object {
        const val LIST_LIMIT = 25
        const val LIST_CHARS = 5_000
        const val SUMMARY_CHARS = 2_500
        const val MAX_REQUESTS = 10
        const val PAGE_CHARS = 7_000
        const val TURN_CHARS = 3_000
    }
}
