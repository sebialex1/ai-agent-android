package com.sebialex.agent.domain.agent

/**
 * `@path` mentions in a user message, e.g. `@imports/photo.jpg`. Imports from the "+" sheet
 * are sent this way, so the bubble can show a preview and the agent knows which file is meant.
 * A mention starts at the beginning of the text or after whitespace, so an e-mail address
 * (`me@example.com`) is never one.
 */
object Mentions {
    /** `@path` or, for names with spaces, `@"path with spaces"`. */
    private val MENTION = Regex("(?<=^|\\s)@(?:\"([^\"\\n]+)\"|(\\S+))")

    private fun pathOf(m: MatchResult): String =
        m.groupValues[1].ifEmpty { m.groupValues[2].trimEnd(',', '.', ';', ':', '!', '?', ')') }

    /** Mentioned paths, in order, without duplicates and without trailing punctuation. */
    fun paths(text: String): List<String> =
        MENTION.findAll(text).map { pathOf(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** [text] without the mentions of [paths] (other text and mentions stay as typed). */
    fun strip(text: String, paths: Collection<String>): String {
        if (paths.isEmpty()) return text.trim()
        return MENTION.replace(text) { m ->
            val p = pathOf(m)
            if (p !in paths) m.value
            // Quoted mentions go whole; a bare one keeps any trailing punctuation it carried.
            else if (m.groupValues[1].isNotEmpty()) "" else m.value.removePrefix("@$p")
        }.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** Character ranges of every mention in [text], for highlighting. */
    fun ranges(text: String): List<IntRange> = MENTION.findAll(text).map { it.range }.toList()

    /** The mention text for [path]: bare when it has no whitespace, quoted otherwise. */
    fun format(path: String): String = if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    fun isImage(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE_EXT
}
