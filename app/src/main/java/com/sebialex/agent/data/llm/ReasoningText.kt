package com.sebialex.agent.data.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where OpenAI-compatible providers put reasoning text besides the usual `reasoning` /
 * `reasoning_content` fields, so it is shown as thinking instead of leaking into the answer:
 *
 * - a `thinking` string next to `content` (Ollama's field name, also used by some compatible servers);
 * - Mistral reasoning models: `content` is a list of chunks, `{"type": "thinking", "thinking": [TextChunk]}`
 *   for the trace and `{"type": "text", "text": ...}` for the answer (also while streaming,
 *   docs.mistral.ai/capabilities/reasoning);
 * - reasoning written inline at the very start of the answer as `<think>...</think>` (DeepSeek R1 style
 *   servers without a reasoning parser), `<thinking>` or `<thought>` (Gemini thought summaries
 *   through some gateways). Only a tag that opens the answer counts; tags later in the text are
 *   left alone.
 */
object ReasoningText {

    /** Field names of a plain reasoning string in a message or delta, in order of preference. */
    val FIELDS = listOf("reasoning", "reasoning_content", "thinking")

    /** The first non-empty reasoning string of [o] under one of [FIELDS]. */
    fun plain(o: JsonObject): String? = FIELDS.firstNotNullOfOrNull { k ->
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    /** Answer text and thinking text of a `content` value: a plain string or a list of content parts. */
    fun split(content: JsonElement?): Pair<String, String> = when (content) {
        is JsonPrimitive -> (if (content.isString) content.content else "") to ""
        is JsonArray -> {
            val text = StringBuilder()
            val thinking = StringBuilder()
            content.forEach { part ->
                val o = part as? JsonObject ?: return@forEach
                val type = (o["type"] as? JsonPrimitive)?.content
                if (type == "thinking") thinking.append(textOf(o["thinking"]))
                else (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { text.append(it) }
            }
            text.toString() to thinking.toString()
        }
        else -> "" to ""
    }

    /** Text of a `thinking` value: a string, or a list of text chunks. */
    private fun textOf(el: JsonElement?): String = when (el) {
        is JsonPrimitive -> if (el.isString) el.content else ""
        is JsonArray -> el.joinToString("") { c -> ((c as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
        else -> ""
    }

    /** One piece of streamed content after separating inline reasoning. */
    data class Piece(val content: String, val reasoning: String) {
        operator fun plus(o: Piece) = Piece(content + o.content, reasoning + o.reasoning)
    }

    /** [text] with a leading inline reasoning block moved out (whole-message form of [InlineSplitter]). */
    fun splitInline(text: String): Piece = InlineSplitter().let { it.feed(text) + it.finish() }

    /**
     * Streams content and moves a reasoning block that opens the answer (`<think>`, `<thinking>`,
     * `<thought>`) to reasoning. Text is held back only while it could still be the start of an
     * opening tag, or the tail of a closing one, so the answer streams normally otherwise.
     * An unclosed block (the model stopped while thinking) is given back as content at [finish],
     * so a reply is never lost.
     */
    class InlineSplitter {
        private enum class State { DETECTING, THINKING, PASSTHROUGH }
        private var state = State.DETECTING
        private val pending = StringBuilder()
        private val thought = StringBuilder()
        private var close = ""

        fun feed(chunk: String): Piece {
            if (chunk.isEmpty()) return Piece("", "")
            return when (state) {
                State.PASSTHROUGH -> Piece(chunk, "")
                State.DETECTING -> {
                    pending.append(chunk)
                    val t = pending.toString().trimStart()
                    if (t.isEmpty()) return Piece("", "")
                    val open = TAGS.keys.firstOrNull { t.startsWith(it, ignoreCase = true) }
                    when {
                        open != null -> {
                            state = State.THINKING
                            close = TAGS.getValue(open)
                            val rest = t.substring(open.length)
                            pending.clear()
                            thinking(rest)
                        }
                        TAGS.keys.any { it.startsWith(t, ignoreCase = true) } -> Piece("", "")
                        else -> {
                            state = State.PASSTHROUGH
                            val all = pending.toString()
                            pending.clear()
                            Piece(all, "")
                        }
                    }
                }
                State.THINKING -> thinking(chunk)
            }
        }

        private fun thinking(chunk: String): Piece {
            pending.append(chunk)
            val s = pending.toString()
            val end = s.indexOf(close, ignoreCase = true)
            if (end >= 0) {
                state = State.PASSTHROUGH
                val reasoning = s.substring(0, end)
                thought.append(reasoning)
                pending.clear()
                return Piece(s.substring(end + close.length).trimStart('\n', '\r', ' '), reasoning)
            }
            // Keep a possible partial closing tag; the rest is reasoning.
            val keep = (close.length - 1).coerceAtMost(s.length)
            val out = s.substring(0, s.length - keep)
            pending.delete(0, out.length)
            thought.append(out)
            return Piece("", out)
        }

        /** Flushes what is held back at the end of the stream. */
        fun finish(): Piece = when (state) {
            State.DETECTING -> Piece(pending.toString(), "").also { pending.clear() }
            State.THINKING -> {
                // Never closed: give the text back as the answer instead of losing it.
                val all = thought.toString() + pending.toString()
                pending.clear()
                state = State.PASSTHROUGH
                reclaimed = true
                Piece(all.trim(), "")
            }
            State.PASSTHROUGH -> Piece("", "")
        }

        /** True once [finish] gave an unclosed block back as content (its reasoning deltas were the answer). */
        var reclaimed = false
            private set

        private companion object {
            val TAGS = linkedMapOf(
                "<think>" to "</think>",
                "<thinking>" to "</thinking>",
                "<thought>" to "</thought>",
            )
        }
    }
}
