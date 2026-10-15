package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn

/**
 * The app's own record of what earlier answers did (tools run, files written or attached,
 * questions asked, the plan), so a follow-up has real context.
 *
 * This record is app-made context, not something the model said. It used to be appended to each
 * replayed assistant message under an `[actions taken in this turn]` header. The model then saw
 * its own earlier replies ending with that list and copied the format into new answers, which
 * the user saw and which were saved with the conversation. So the record now goes only into the
 * system prompt ([context]), and the assistant messages replayed to the model are exactly the text
 * the user saw.
 *
 * [stripEcho] removes that exact legacy block, and only that block (the app's own header plus
 * the list under it). It runs on answers coming from the model and on turns saved by older
 * builds, so an old copy can neither reach the screen nor go back to the model as an example.
 */
object TurnRecord {

    /** Header of the block that older builds appended to replayed answers. */
    const val LEGACY_HEADER = "[actions taken in this turn]"

    /** One line per thing [t] did, newest last; empty when it only answered. */
    fun lines(t: Turn): List<String> {
        val done = t.steps.filter { it.status == StepStatus.DONE && it.tool != "permission" && it.tool != "reasoning" }
        return done.takeLast(12).map { "- ${it.tool}: ${it.payload.take(160)}" } +
            t.attachments.map { "- file: ${it.path}" } +
            t.artifacts.map { "- attached: ${it.path}" } +
            t.questions.map { "- asked the user: ${it.question.take(160)} -> ${it.answer?.take(160) ?: "(no answer)"}" } +
            t.subagents.map { "- subagent (${it.status.name.lowercase()}): ${it.objective.take(120)} -> ${it.result.replace(Regex("\\s+"), " ").take(160)}" } +
            listOfNotNull(t.plan?.let { p -> "- plan \"${p.title}\": " + p.steps.joinToString("; ") { "${it.text} (${it.status.name.lowercase()})" }.take(400) })
    }

    /**
     * System-prompt section listing what the earlier answers in [history] did, each under the
     * request it answered. Empty when none of them used a tool.
     */
    fun context(history: List<Turn>): String {
        val entries = mutableListOf<String>()
        var request = ""
        var answer = 0
        for (t in history) {
            if (t.role == Role.USER) { request = t.text; continue }
            answer++
            val l = lines(t)
            if (l.isEmpty()) continue
            val asked = request.replace(Regex("\\s+"), " ").trim().take(80)
            entries += "Answer $answer (to \"$asked\"):\n" + l.joinToString("\n")
        }
        if (entries.isEmpty()) return ""
        return "\n# Earlier answers: what they did\n" +
            "Kept by the app so follow-ups have context. This is not part of the conversation and the user never sees it: " +
            "never write it, a summary of it, or a similar list of actions or tools into a reply.\n" +
            entries.joinToString("\n") + "\n"
    }

    /**
     * [text] without an echoed [LEGACY_HEADER] block: the header line and the `- …` lines that
     * follow it. Everything else is kept exactly as written.
     */
    fun stripEcho(text: String): String {
        if (!text.contains(LEGACY_HEADER, ignoreCase = true)) return text
        val out = mutableListOf<String>()
        var inBlock = false
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.equals(LEGACY_HEADER, ignoreCase = true) -> inBlock = true
                inBlock && t.startsWith("- ") -> Unit
                else -> { inBlock = false; out += line }
            }
        }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** [turn] with any echoed record removed from an assistant answer (older saves). */
    fun clean(turn: Turn): Turn =
        if (turn.role == Role.ASSISTANT && turn.text.contains(LEGACY_HEADER, ignoreCase = true)) turn.copy(text = stripEcho(turn.text)) else turn
}
