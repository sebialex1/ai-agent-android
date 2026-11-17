package com.sebialex.agent

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.sebialex.agent.ui.chat.activeEmoji
import com.sebialex.agent.ui.chat.applyEmoji
import com.sebialex.agent.ui.chat.emojiFor
import com.sebialex.agent.ui.chat.parseEmojiCodes
import com.sebialex.agent.ui.chat.suggestEmoji
import com.sebialex.agent.ui.chat.withParsedEmojis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deterministic emoji-code contract: detection, suggestions, parsing. */
class EmojiTest {

    private fun value(text: String, cursor: Int = text.length) = TextFieldValue(text, TextRange(cursor))

    @Test
    fun `active emoji opens on a colon at the cursor`() {
        val a = activeEmoji(value("hello :rof"))
        assertEquals(6, a!!.start)
        assertEquals(10, a.end)
        assertEquals("rof", a.query)
    }

    @Test
    fun `active emoji needs a word boundary before the colon`() {
        assertNull(activeEmoji(value("http://x")))
        assertNull(activeEmoji(value("abc:de")))
    }

    @Test
    fun `active emoji ignores whitespace and long or invalid queries`() {
        assertNull(activeEmoji(value("a :hello world")))
        assertNull(activeEmoji(value(":${"a".repeat(33)}")))
        assertNull(activeEmoji(value(":he llo")))
    }

    @Test
    fun `a bare colon opens the popup with the popular codes`() {
        val a = activeEmoji(value("hi :"))
        assertEquals("", a!!.query)
        assertTrue(suggestEmoji(a.query).isNotEmpty())
        val bare = activeEmoji(value(":"))
        assertEquals("", bare!!.query)
    }

    @Test
    fun `active emoji requires a collapsed cursor`() {
        assertNull(activeEmoji(TextFieldValue(":rofl", TextRange(1, 4))))
    }

    @Test
    fun `suggestions match by prefix and fall back to popular codes`() {
        val rofl = suggestEmoji("rofl")
        assertTrue(rofl.first().code == "rofl")
        assertTrue(suggestEmoji("ROF").first().code == "rofl")
        assertTrue(suggestEmoji("zzzznonsense").isEmpty())
        val all = suggestEmoji("")
        assertEquals(24, all.size)
        assertTrue(all.all { it.emoji.isNotEmpty() })
    }

    @Test
    fun `an empty query puts recents first, then popular fill`() {
        val all = suggestEmoji("").map { it.code }
        val mixed = suggestEmoji("", recent = listOf("fire", "not_a_code", "joy"))
        assertEquals(listOf("fire", "joy"), mixed.take(2).map { it.code })
        assertTrue(mixed.size == 24)
        assertTrue(mixed.drop(2).all { it.code in all })
        // Recents never duplicate the popular fill.
        assertEquals(mixed.size, mixed.map { it.code }.toSet().size)
    }

    @Test
    fun `a typed query ranks prefix matches first, then the rest of the library`() {
        val hits = suggestEmoji("sk", recent = listOf("joy"))
        assertTrue(hits.isNotEmpty())
        // Prefix matches lead; wider matches may follow so the whole library stays reachable.
        assertTrue(hits.first().code.startsWith("sk"))
        assertTrue(hits.takeWhile { it.code.startsWith("sk") }.isNotEmpty())
        assertEquals(hits.size, hits.map { it.code }.toSet().size)
        // A query that is only a word inside a code still finds it.
        assertTrue(suggestEmoji("crossbones").any { it.code.contains("crossbones") })
    }

    @Test
    fun `parse replaces complete known codes only`() {
        assertEquals("\ud83e\udd23", parseEmojiCodes(":rofl:"))
        assertEquals("hey \ud83e\udd23 lol", parseEmojiCodes("hey :rofl: lol"))
        assertEquals(":nope_not_an_emoji:", parseEmojiCodes(":nope_not_an_emoji:"))
        assertEquals(":rofl", parseEmojiCodes(":rofl"))
        assertEquals("a : b", parseEmojiCodes("a : b"))
    }

    @Test
    fun `parse is idempotent and emoji-only text stays`() {
        val parsed = parseEmojiCodes("hey :rofl:")
        assertEquals(parsed, parseEmojiCodes(parsed))
    }

    @Test
    fun `apply replaces the typed code with the emoji`() {
        val v = value("hi :rof")
        val a = activeEmoji(v)!!
        val next = applyEmoji(v, a, "\ud83e\udd23")
        assertEquals("hi \ud83e\udd23", next.text)
        assertEquals("hi \ud83e\udd23".length, next.selection.start)
    }

    @Test
    fun `withParsedEmojis only applies at the end cursor`() {
        val mid = TextFieldValue(":rofl: x", TextRange(2))
        assertEquals(mid, withParsedEmojis(mid))
        val end = value(":rofl:")
        assertEquals("\ud83e\udd23", withParsedEmojis(end).text)
    }

    @Test
    fun `dataset has no duplicate codes and the Discord names`() {
        val codes = com.sebialex.agent.ui.chat.EMOJI_CODES.map { it.first }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.size > 5000)
        assertEquals("\ud83e\udd23", emojiFor("rofl"))
        assertEquals("\ud83d\udc80", emojiFor("skull"))
        assertNull(emojiFor("definitely_missing"))
    }
}
