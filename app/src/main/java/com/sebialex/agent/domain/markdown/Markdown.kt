package com.sebialex.agent.domain.markdown

/** Minimal CommonMark/GFM-flavoured block model used by the reader. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListItem(
        val depth: Int,
        val ordered: Boolean,
        val number: Int,
        val checked: Boolean?,
        val text: String,
        /** Zero-based source line index, used to toggle checkboxes in place. */
        val line: Int,
    ) : MdBlock
    data class Table(val header: List<String>, val align: List<Align>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

enum class Align { START, CENTER, END }

object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val fence = Regex("^\\s*(```|~~~)\\s*([\\w+#.-]*)\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(source: String): List<MdBlock> = parseLines(source.replace("\r\n", "\n").split('\n'), 0)

    private fun parseLines(lines: List<String>, offset: Int): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        var i = 0
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = fence.matchEntire(line)
            when {
                fenceMatch != null -> {
                    flush()
                    val marker = fenceMatch.groupValues[1]
                    val lang = fenceMatch.groupValues[2]
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
                }
                line.isBlank() -> flush()
                heading.matchEntire(line) != null -> {
                    flush()
                    val m = heading.matchEntire(line)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val inner = mutableListOf<String>()
                    val start = i
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        inner += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    out += MdBlock.Quote(parseLines(inner, offset + start))
                    continue
                }
                line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    val header = splitRow(line)
                    val align = splitRow(lines[i + 1]).map {
                        val t = it.trim()
                        when {
                            t.startsWith(":") && t.endsWith(":") -> Align.CENTER
                            t.endsWith(":") -> Align.END
                            else -> Align.START
                        }
                    }
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        val cells = splitRow(lines[i])
                        rows += List(header.size) { idx -> cells.getOrElse(idx) { "" } }
                        i++
                    }
                    out += MdBlock.Table(header, List(header.size) { align.getOrElse(it) { Align.START } }, rows)
                    continue
                }
                bullet.matchEntire(line) != null || ordered.matchEntire(line) != null -> {
                    flush()
                    val b = bullet.matchEntire(line)
                    val indent: Int
                    val isOrdered: Boolean
                    val number: Int
                    var text: String
                    if (b != null) {
                        indent = b.groupValues[1].length; isOrdered = false; number = 0; text = b.groupValues[3]
                    } else {
                        val o = ordered.matchEntire(line)!!
                        indent = o.groupValues[1].length; isOrdered = true; number = o.groupValues[2].toInt(); text = o.groupValues[3]
                    }
                    val itemLine = i
                    var checked: Boolean? = null
                    task.matchEntire(text)?.let {
                        checked = it.groupValues[1].isNotBlank()
                        text = it.groupValues[2]
                    }
                    // lazy continuation lines
                    while (i + 1 < lines.size && lines[i + 1].isNotBlank() && lines[i + 1].startsWith("  ") &&
                        bullet.matchEntire(lines[i + 1]) == null && ordered.matchEntire(lines[i + 1]) == null
                    ) {
                        text += " " + lines[i + 1].trim()
                        i++
                    }
                    out += MdBlock.ListItem(indent / 2, isOrdered, number, checked, text, offset + itemLine)
                }
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(line.trim())
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split('|').map { it.trim() }
    }

    /** Toggles a task checkbox at [line] and returns the new source. */
    fun toggleTask(source: String, line: Int): String {
        val lines = source.split('\n').toMutableList()
        if (line !in lines.indices) return source
        val l = lines[line]
        lines[line] = when {
            l.contains("[ ]") -> l.replaceFirst("[ ]", "[x]")
            l.contains("[x]") -> l.replaceFirst("[x]", "[ ]")
            l.contains("[X]") -> l.replaceFirst("[X]", "[ ]")
            else -> l
        }
        return lines.joinToString("\n")
    }
}

/** Inline span model, converted to AnnotatedString by the UI layer. */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
}

object InlineParser {
    fun parse(text: String): List<MdInline> {
        val out = mutableListOf<MdInline>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out += MdInline.Text(buf.toString()); buf.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> { buf.append(text[i + 1]); i += 2; continue }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { flush(); out += MdInline.Code(text.substring(i + 1, end)); i = end + 1; continue }
                }
                c == '[' -> {
                    val close = findClose(text, i, '[', ']')
                    if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                        val urlEnd = text.indexOf(')', close + 2)
                        if (urlEnd > 0) {
                            flush()
                            out += MdInline.Link(parse(text.substring(i + 1, close)), text.substring(close + 2, urlEnd).trim())
                            i = urlEnd + 1
                            continue
                        }
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) { flush(); out += MdInline.Bold(parse(text.substring(i + 2, end))); i = end + 2; continue }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) { flush(); out += MdInline.Strike(parse(text.substring(i + 2, end))); i = end + 2; continue }
                }
                (c == '*' || c == '_') && i + 1 < text.length && text[i + 1] != ' ' -> {
                    val end = text.indexOf(c, i + 1)
                    val wordBoundary = c == '*' || i == 0 || !text[i - 1].isLetterOrDigit()
                    if (end > i + 1 && wordBoundary) { flush(); out += MdInline.Italic(parse(text.substring(i + 1, end))); i = end + 1; continue }
                }
                text.startsWith("https://", i) || text.startsWith("http://", i) -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] != ')') end++
                    val url = text.substring(i, end).trimEnd('.', ',', ';')
                    flush(); out += MdInline.Link(listOf(MdInline.Text(url)), url); i += url.length; continue
                }
            }
            buf.append(c)
            i++
        }
        flush()
        return out
    }

    private fun findClose(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        for (j in start until text.length) {
            if (text[j] == open) depth++
            if (text[j] == close) { depth--; if (depth == 0) return j }
        }
        return -1
    }
}
