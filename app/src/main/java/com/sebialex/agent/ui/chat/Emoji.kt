package com.sebialex.agent.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** One emoji suggestion: its shortcode and the emoji itself. */
data class EmojiEntry(val code: String, val emoji: String)

/** The `:query` being typed at the cursor: where the `:` is and what follows it. */
data class ActiveEmoji(val start: Int, val end: Int, val query: String)

/**
 * The emoji code under the cursor, or null. A code starts with `:` at the beginning of the text
 * or after whitespace (so "http://" never opens the popup) and runs to the cursor without
 * whitespace; a bare `:` opens the popup with the popular codes. The query may only hold
 * shortcode characters and stays under [MAX_QUERY] long. Only a collapsed cursor counts.
 */
fun activeEmoji(value: TextFieldValue): ActiveEmoji? {
    val sel = value.selection
    if (!sel.collapsed) return null
    val text = value.text
    val cursor = sel.end.coerceIn(0, text.length)
    var i = cursor - 1
    while (i >= 0 && !text[i].isWhitespace() && text[i] != ':') i--
    if (i < 0 || text[i] != ':') return null
    if (i > 0 && !text[i - 1].isWhitespace()) return null
    val query = text.substring(i + 1, cursor)
    if (query.length > MAX_QUERY) return null
    if (query.isNotEmpty() && !query.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '+' }) return null
    return ActiveEmoji(i, cursor, query.lowercase())
}

/**
 * Shortcodes matching [query], best first: codes that start with it, then codes with a word
 * starting with it, then any code containing it — so the whole bundled library is reachable,
 * not only the codes that begin with what was typed. For an empty query the user's [recent]
 * codes come first, then the most popular ones to fill the row.
 */
fun suggestEmoji(query: String, recent: List<String> = emptyList(), limit: Int = MAX_SHOWN): List<EmojiEntry> {
    val q = query.lowercase()
    val out = ArrayList<EmojiEntry>(limit)
    val seen = HashSet<String>()
    fun add(code: String, emoji: String): Boolean {
        if (!seen.add(code)) return out.size >= limit
        out += EmojiEntry(code, emoji)
        return out.size >= limit
    }
    if (q.isEmpty()) {
        val byCode = EMOJI_BY_CODE
        for (code in recent) {
            val emoji = byCode[code] ?: continue
            if (add(code, emoji)) return out
        }
    }
    for ((code, emoji) in EMOJI_CODES) if (code.startsWith(q) && add(code, emoji)) return out
    if (q.isNotEmpty()) {
        for ((code, emoji) in EMOJI_CODES) if (code.contains("_$q") && add(code, emoji)) return out
        for ((code, emoji) in EMOJI_CODES) if (code.contains(q) && add(code, emoji)) return out
    }
    return out
}

/** Lookup for [EMOJI_CODES]; built once, used to resolve the recents. */
private val EMOJI_BY_CODE: Map<String, String> by lazy { EMOJI_CODES.toMap() }

/** Replaces every complete `:code:` in [text] with its emoji; unknown codes stay as typed. */
fun parseEmojiCodes(text: String): String {
    if (':' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c != ':') {
            out.append(c)
            i++
            continue
        }
        val close = text.indexOf(':', i + 1)
        if (close <= i + 1) {
            out.append(c)
            i++
            continue
        }
        val code = text.substring(i + 1, close)
        val emoji = emojiFor(code)
        if (emoji != null) {
            out.append(emoji)
            i = close + 1
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** The emoji for a shortcode, or null when unknown. */
fun emojiFor(code: String): String? = EMOJI_BY_CODE[code.lowercase()]

/** Replaces the typed `:query` with [emoji] itself. */
fun applyEmoji(value: TextFieldValue, active: ActiveEmoji, emoji: String): TextFieldValue {
    val text = value.text
    val next = text.substring(0, active.start) + emoji + text.substring(active.end)
    return TextFieldValue(next, TextRange(active.start + emoji.length))
}

/**
 * The typed value with completed `:code:` sequences turned into emoji. Only applied while the
 * cursor is at the end (where typing happens), so editing earlier text never moves the cursor.
 */
fun withParsedEmojis(value: TextFieldValue): TextFieldValue {
    val text = value.text
    if (value.selection.end != text.length) return value
    val parsed = parseEmojiCodes(text)
    if (parsed == text) return value
    return TextFieldValue(parsed, TextRange(parsed.length))
}

private const val MAX_QUERY = 32
private const val MAX_SHOWN = 24
