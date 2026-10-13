package com.sebialex.agent.domain.agent

/**
 * Decides whether an imported image is shown to the model with the message (vision), from what
 * the user wrote next to it:
 *
 * - nothing but the import ([Intent.ONLY_MEDIA]), an ask to look at it ("what's this",
 *   "describe", "read the text": [Intent.ASKED_TO_LOOK]) or any other request about it
 *   ([Intent.IMPLICIT]) -> shown with the message;
 * - only an instruction to use or handle the file ("use this as the background", "upload it",
 *   "convert to png": [Intent.USE_ONLY]) -> not shown automatically; the model may still call
 *   `view_image` on it if the task turns out to need the picture;
 * - an explicit refusal ("don't look", "don't describe it", "ignore the image":
 *   [Intent.REFUSED]) -> not shown, and `view_image` is refused for the whole turn. This is the
 *   only case that blocks `view_image`.
 *
 * The two "not shown" cases are kept apart so the audit log and the note to the model say which
 * one applied.
 */
object VisionIntent {

    /** What the user's words ask for. [shown]: sent with the message; [viewImage]: the model may call view_image on the import. */
    enum class Intent(val shown: Boolean, val viewImage: Boolean) {
        ONLY_MEDIA(shown = true, viewImage = true),
        ASKED_TO_LOOK(shown = true, viewImage = true),
        IMPLICIT(shown = true, viewImage = true),
        // Not inspected automatically, but the model may still look on demand if the task needs it.
        USE_ONLY(shown = false, viewImage = true),
        REFUSED(shown = false, viewImage = false),
    }

    private val DONT_LOOK = Regex(
        "\\b(don['’]?t|do not|no need to|without|never|no)\\s+(look|looking|see|seeing|view|viewing|open|opening|analy[sz]e|analy[sz]ing|describe|describing)\\b",
    )

    private val IGNORE = Regex(
        "\\b(ignore|disregard|skip)\\s+(it|this|that|them|the\\s+(image|images|picture|pictures|photo|photos|video|videos|file|files|attachment|attachments))\\b",
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

    /** What [task] (a message with `@path` imports) asks for, by the rules above. */
    fun classify(task: String): Intent {
        val text = Mentions.strip(task, Mentions.paths(task)).lowercase().trim()
        return when {
            text.isEmpty() -> Intent.ONLY_MEDIA
            // A refusal wins over everything else, even "look" in "don't look".
            DONT_LOOK.containsMatchIn(text) || IGNORE.containsMatchIn(text) -> Intent.REFUSED
            LOOK.containsMatchIn(text) -> Intent.ASKED_TO_LOOK
            USE_ONLY.containsMatchIn(text) -> Intent.USE_ONLY
            // Any other text (a question about it, "make a caption", "is this ok?") needs the picture.
            else -> Intent.IMPLICIT
        }
    }

    /** True when the images mentioned in [task] should be shown to the model with it. */
    fun shouldLook(task: String): Boolean = classify(task).shown
}
