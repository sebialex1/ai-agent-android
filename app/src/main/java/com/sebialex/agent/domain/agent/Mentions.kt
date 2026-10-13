package com.sebialex.agent.domain.agent

/**
 * `@path` mentions in a user message, e.g. `@imports/photo.jpg`. Imports from the "+" sheet
 * are sent this way, so the bubble can show a preview and the agent knows which file is meant.
 * A mention starts at the beginning of the text or after whitespace, so an e-mail address
 * (`me@example.com`) is never one.
 */
object Mentions {
    private val MENTION = Regex("(?<=^|\\s)@(\\S+)")

    /** Mentioned paths, in order, without duplicates and without trailing punctuation. */
    fun paths(text: String): List<String> =
        MENTION.findAll(text).map { it.groupValues[1].trimEnd(',', '.', ';', ':', '!', '?', ')') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** [text] without the mentions of [paths] (other text and mentions stay as typed). */
    fun strip(text: String, paths: Collection<String>): String {
        if (paths.isEmpty()) return text.trim()
        return MENTION.replace(text) { m ->
            val p = m.groupValues[1].trimEnd(',', '.', ';', ':', '!', '?', ')')
            if (p in paths) m.value.removePrefix("@$p") else m.value
        }.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    fun isImage(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE_EXT
}
