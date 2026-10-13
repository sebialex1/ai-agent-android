package com.sebialex.agent.domain.agent

/**
 * Decides whether an imported image is shown to the model with the message (vision), from what
 * the user wrote next to it:
 *
 * - nothing but the import, or an ask to look at it ("what's this", "describe", "read the
 *   text", a question about it) -> look;
 * - an explicit "don't look", or only an instruction to use or handle the file ("use this as
 *   the background", "upload it", "convert to png", "rename it") -> don't look. The model is told
 *   the file exists and can still call `view_image` if it decides it needs to see it;
 * - anything else -> look, since a message about an attached image almost always needs it.
 */
object VisionIntent {

    private val DONT_LOOK = Regex(
        "\\b(don['’]?t|do not|no need to|without|never|no)\\s+(look|looking|see|seeing|view|viewing|open|opening|analy[sz]e|analy[sz]ing|describe|describing)\\b",
    )

    private val LOOK = Regex(
        "\\b(look|see|view|show me|describe|explain|what['’]?s|what is|what are|whats|what does|who is|who['’]?s|where is|identify|recogni[sz]e|" +
            "read|ocr|transcribe|caption|analy[sz]e|check|inspect|examine|tell me|thoughts|opinion|rate|review|translate|solve|count|" +
            "compare|summari[sz]e|extract|find|spot|notice|uit[aă]|vezi|ce e|ce este|descrie|spune|analizeaz[aă]|cite[sș]te)\\b",
    )

    private val USE_ONLY = Regex(
        "\\b(use|using|attach|upload|send|save|store|keep|move|rename|copy|convert|compress|resize|scale|crop|rotate|flip|" +
            "embed|insert|put|place|set|add|zip|unzip|share|post|email|backup|back up|download|delete|remove|encode|transcode|" +
            "folose[sș]te|pune|trimite|salveaz[aă]|mut[aă])\\b",
    )

    /** True when the images mentioned in [task] should be shown to the model with it. */
    fun shouldLook(task: String): Boolean {
        val text = Mentions.strip(task, Mentions.paths(task)).lowercase().trim()
        if (text.isEmpty()) return true
        if (DONT_LOOK.containsMatchIn(text)) return false
        if (LOOK.containsMatchIn(text)) return true
        if (USE_ONLY.containsMatchIn(text)) return false
        // Any other text (a question about it, "make a caption", "is this ok?") needs the picture.
        return true
    }
}
