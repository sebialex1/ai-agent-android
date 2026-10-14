package com.sebialex.agent.domain.agent

/**
 * Decides whether one file of an `attach_files` call may become an artifact of the answer.
 *
 * Artifacts only ever come from a real, structured `attach_files` tool call (never from another
 * tool's side effect, never from tool-call JSON the model wrote into its answer text). Even then
 * a file is attached only when the user plausibly wants it:
 *
 * - it was created or changed during this turn (downloaded, converted, generated, written), or
 * - the user mentioned it in the message (`@path`, an import, or its file name), or
 * - the user asked for a file to be shown, sent or attached ("send me the video", "attach it").
 *
 * Any other file (an old workspace file the user did not ask about) is refused with a reason the
 * model can read, so it can mention the file in words instead.
 */
object AttachPolicy {

    /** The user asks to be shown, sent or given a file (English and Romanian). */
    private val ASKS_FOR_FILE = Regex(
        "\\b(attach|attached|send|share|show|give me|hand me|let me (see|have|download)|display|open|play|preview|export|deliver|" +
            "download|get me|find me|fetch me|i want the|i need the|where is|where's|" +
            "ata[sș]eaz[aă]|trimite|arat[aă]|d[aă]-mi|vreau|deschide)\\b",
    )

    /** Why [path] must not be attached in this turn, or null when it may be. */
    fun refusal(path: String, task: String, producedThisTurn: Boolean): String? {
        if (producedThisTurn) return null
        if (mentioned(path, task)) return null
        if (asksForFile(task)) return null
        return "$path: not attached. It was not created or changed in this turn and the user did not ask for it. " +
            "Mention it in your answer instead if it is relevant."
    }

    /** True when the user's message asks for a file to be shown, sent or attached. */
    fun asksForFile(task: String): Boolean =
        ASKS_FOR_FILE.containsMatchIn(Mentions.strip(task, Mentions.paths(task)).lowercase())

    /** The user mentioned [path] as `@path` or wrote its file name (with or without extension). */
    fun mentioned(path: String, task: String): Boolean {
        val norm = normalize(path)
        if (Mentions.paths(task).any { normalize(it) == norm }) return true
        val name = norm.substringAfterLast('/')
        val stem = name.substringBeforeLast('.', name)
        val text = task.lowercase()
        return name.isNotEmpty() && name in text || stem.length >= 4 && stem in text
    }

    private fun normalize(p: String): String =
        "/" + p.trim().removePrefix("/workspace").trim('/').lowercase()
}
