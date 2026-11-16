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

/** Shortcodes that start with [query] (best/popular first); the most popular ones for "". */
fun suggestEmoji(query: String, limit: Int = MAX_SHOWN): List<EmojiEntry> {
    val q = query.lowercase()
    val out = ArrayList<EmojiEntry>(limit)
    for ((code, emoji) in EMOJI_CODES) {
        if (q.isNotEmpty() && !code.startsWith(q)) continue
        out += EmojiEntry(code, emoji)
        if (out.size >= limit) break
    }
    return out
}

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
fun emojiFor(code: String): String? {
    val c = code.lowercase()
    // Linear scan is fine at 2k entries and keeps the dataset a plain list.
    return EMOJI_CODES.firstOrNull { it.first == c }?.second
}

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
private const val MAX_SHOWN = 14
