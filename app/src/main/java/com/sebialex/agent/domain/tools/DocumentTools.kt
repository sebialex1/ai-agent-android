package com.sebialex.agent.domain.tools

import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.data.workspace.WorkspaceException
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Office and PDF documents for the workspace: extract text from .docx/.pptx/.xlsx/.odt/.odp/.ods/
 * .pdf/.rtf (read_file does this automatically), and write precise .docx and .pdf files.
 * Everything is deterministic: ZIP plus DOM for OOXML/ODF, PDFBox for PDF text extraction and
 * creation, no external process involved.
 */
internal object DocumentTools {

    /** File name extensions that can carry model-readable text for the agent. */
    val EXTENSIONS = setOf("docx", "pptx", "xlsx", "odt", "odp", "ods", "pdf", "rtf")

    /** Upper bound for extracted text per read; longer documents are cut with a note. */
    private const val MAX_CHARS = 120_000

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun handles(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in EXTENSIONS

    // ---------------------------------------------------------------- extract

    /** Human-readable extracted text of a supported document, prefixed with what it is. */
    fun extract(file: File): String {
        val ext = file.extension.lowercase()
        val label = when (ext) {
            "pdf" -> "PDF document"
            "docx" -> "Word document (.docx)"
            "pptx" -> "PowerPoint deck (.pptx)"
            "xlsx" -> "Excel workbook (.xlsx)"
            "rtf" -> "Rich text (.rtf)"
            else -> "OpenDocument (.$ext)"
        }
        val text = when (ext) {
            "pdf" -> pdfText(file)
            "docx" -> docxText(file)
            "pptx" -> pptxText(file)
            "xlsx" -> xlsxText(file)
            "rtf" -> rtfText(file)
            else -> odfText(file)
        }.trim()
        return buildString {
            append(label).append(" (").append(file.length()).append(" bytes) — extracted text:\n")
            if (text.isEmpty()) append("(no text content)") else if (text.length <= MAX_CHARS) append(text)
            else append(text, 0, MAX_CHARS).append("\n[text truncated at ").append(MAX_CHARS).append(" characters]")
        }
    }

    private fun pdfText(file: File): String = try {
        PDDocument.load(file).use { doc -> PDFTextStripper().getText(doc) }
    } catch (e: Exception) {
        throw WorkspaceException("could not read this PDF: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun docxText(file: File): String {
        val xml = zipEntry(file, "word/document.xml") ?: throw WorkspaceException("${file.name}: not a Word .docx file")
        // One line per paragraph, so the model can reference paragraphs exactly.
        return walkParagraphs(dom(xml), paragraphTags = setOf("p"), lineBreakTags = setOf("br", "tab"))
    }

    private fun pptxText(file: File): String {
        val slides = zipEntries(file).filterKeys { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
            .toSortedMap(Comparator { a, b -> slideNumber(a) - slideNumber(b) })
        if (slides.isEmpty()) throw WorkspaceException("${file.name}: not a PowerPoint .pptx file")
        return slides.entries.joinToString("\n\n") { (name, xml) ->
            "Slide ${name.substringAfter("slide").substringBefore('.')}:\n" +
                walkParagraphs(dom(xml), setOf("p"), setOf("br", "tab"))
        }
    }

    private fun xlsxText(file: File): String {
        val entries = zipEntries(file)
        val shared = entries["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val sheets = entries.filterKeys { it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }
            .toSortedMap(Comparator { a, b -> slideNumber(a) - slideNumber(b) })
        if (sheets.isEmpty()) throw WorkspaceException("${file.name}: not an Excel .xlsx file")
        return sheets.entries.joinToString("\n\n") { (name, xml) ->
            "Sheet ${name.substringAfter('/').substringBefore('.')}:\n" + sheetRows(dom(xml), shared)
        }
    }

    /** Numeric part of .../slide10.xml or .../sheet1.xml. */
    private fun slideNumber(entryName: String): Int =
        Regex("(?:slide|sheet)([0-9]+)\\.xml$").find(entryName)!!.groupValues[1].toInt()

    private fun odfText(file: File): String {
        val xml = zipEntry(file, "content.xml") ?: throw WorkspaceException("${file.name}: not an OpenDocument file")
        return walkParagraphs(dom(xml), paragraphTags = setOf("p", "h"), lineBreakTags = setOf("tab", "line-break"))
    }

    /** DOM walker collecting one line per paragraph; local-name based, so namespaces do not matter. */
    private fun walkParagraphs(doc: Document, paragraphTags: Set<String>, lineBreakTags: Set<String>): String {
        val out = StringBuilder()
        fun walk(n: Node, inParagraph: Boolean) {
            if (n.nodeType == Node.TEXT_NODE) {
                if (inParagraph) out.append(n.nodeValue)
                return
            }
            if (n.nodeType != Node.ELEMENT_NODE) return
            val local = (n as Element).localName ?: n.nodeName
            when {
                local in paragraphTags -> {
                    if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                    for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i), inParagraph = true)
                }
                local in lineBreakTags -> out.append(if (local == "tab") '\t' else '\n')
                else -> for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i), inParagraph)
            }
        }
        walk(doc.documentElement, inParagraph = false)
        return out.toString()
    }

    private fun sheetRows(doc: Document, shared: List<String>): String {
        val out = StringBuilder()
        val rows = doc.getElementsByTagNameNS("*", "row")
        for (i in 0 until rows.length) {
            val cells = StringBuilder()
            val cs = (rows.item(i) as Element).getElementsByTagNameNS("*", "c")
            for (j in 0 until cs.length) {
                if (j > 0) cells.append('\t')
                val c = cs.item(j) as Element
                val ts = c.getElementsByTagNameNS("*", "t")
                val vs = c.getElementsByTagNameNS("*", "v")
                when (c.getAttribute("t")) {
                    "inlineStr" -> if (ts.length > 0) cells.append(ts.item(0).textContent)
                    "s" -> vs.item(0)?.textContent?.toIntOrNull()?.let { cells.append(shared.getOrNull(it) ?: "") }
                    else -> vs.item(0)?.let { cells.append(it.textContent) }
                }
            }
            out.append(cells).append('\n')
        }
        return out.toString().trimEnd('\n')
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val sis = dom(xml).getElementsByTagNameNS("*", "si")
        for (i in 0 until sis.length) {
            val sb = StringBuilder()
            collectText(sis.item(i), sb)
            out += sb.toString()
        }
        return out
    }

    private fun collectText(n: Node, sb: StringBuilder) {
        if (n.nodeType == Node.TEXT_NODE) { sb.append(n.nodeValue); return }
        if (n.nodeType != Node.ELEMENT_NODE) return
        if (((n as Element).localName ?: n.nodeName) == "t") { sb.append(n.textContent); return }
        for (i in 0 until n.childNodes.length) collectText(n.childNodes.item(i), sb)
    }

    /** Minimal RTF reader: strips control words, groups and ignorable destinations (fonttbl etc.). */
    private fun rtfText(file: File): String {
        val data = String(file.readBytes(), Charsets.ISO_8859_1)
        val out = StringBuilder()
        val skipDestinations = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "xmlnstbl", "object", "pict", "themedata",
            "rsidtbl", "generator", "filetbl", "latentstyles", "datastore", "listtable",
            "listoverridetable", "flatfile", "header", "footer",
        )
        var i = 0
        var depth = 0
        val skip = ArrayDeque<Int>() // brace depths of groups whose content is ignored
        while (i < data.length) {
            when (val c = data[i]) {
                '{' -> { depth++; i++ }
                '}' -> { if (skip.isNotEmpty() && skip.last() == depth) skip.removeLast(); depth--; i++ }
                '\\' -> {
                    i++
                    if (i >= data.length) break
                    when (val n = data[i]) {
                        '\'' -> {
                            if (i + 2 < data.length && data[i + 1].isHex() && data[i + 2].isHex()) {
                                if (skip.isEmpty()) out.append(data.substring(i + 1, i + 3).toInt(16).toChar())
                                i += 3
                            } else i++
                        }
                        in 'a'..'z', in 'A'..'Z' -> {
                            var j = i
                            while (j < data.length && data[j].isLetter()) j++
                            val word = data.substring(i, j)
                            var k = j
                            if (k < data.length && data[k] == '-') k++
                            while (k < data.length && data[k].isDigit()) k++
                            if (k < data.length && k == j && data[k] == ' ') k++ // one space after a control word
                            if (skip.isEmpty()) when (word) {
                                "par", "sect", "page", "line" -> out.append('\n')
                                "tab" -> out.append('\t')
                            }
                            if (word in skipDestinations) skip.addLast(depth) else i = k
                            if (word in skipDestinations) i = k
                        }
                        '*' -> { if (skip.isEmpty() || skip.last() != depth) skip.addLast(depth); i++ }
                        else -> { if (skip.isEmpty()) out.append(n); i++ }
                    }
                }
                '\n', '\r' -> i++
                else -> { if (skip.isEmpty()) out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // ---------------------------------------------------------------- the tool

    /** Runs the document tool against the workspace. */
    fun run(ws: Workspace, call: ToolCall.Document): ToolResult {
        val path = call.path.trim().trim('/')
        if (path.isEmpty()) return ToolResult(call.callId, false, "Give me a file path ending in .docx or .pdf.")
        val ext = path.substringAfterLast('.', "").lowercase()
        val action = call.action.trim().lowercase()
        return try {
            when (action) {
                "create" -> {
                    val content = call.content
                    if (content.isNullOrBlank()) return ToolResult(call.callId, false, "action create needs content.")
                    val bytes = when (ext) {
                        "docx" -> buildDocx(content)
                        "pdf" -> buildPdf(content)
                        else -> return ToolResult(call.callId, false, "\"$path\": I can create .docx and .pdf here; other formats stay plain text files.")
                    }
                    val f = ws.resolve(path)
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                    ToolResult(call.callId, true, "created ${ws.virtualOf(f)} (${bytes.size} bytes)",
                        attachment = FileAttachment(path = ws.virtualOf(f), name = f.name, updatedAt = System.currentTimeMillis()))
                }
                "replace" -> {
                    val oldText = call.oldText
                    if (oldText.isNullOrEmpty()) return ToolResult(call.callId, false, "action replace needs old_text.")
                    if (ext == "pdf") return ToolResult(call.callId, false, "${call.path}: PDFs cannot be edited in place — create a new one with action create.")
                    if (ext != "docx") return ToolResult(call.callId, false, "${call.path}: only .docx documents are edited here. For plain files use edit_file.")
                    val f = ws.resolve(path)
                    if (!f.isFile) return ToolResult(call.callId, false, "${call.path}: no such file.")
                    val (newBytes, count) = replaceDocx(f.readBytes(), oldText, call.newText.orEmpty(), call.replaceAll)
                    f.writeBytes(newBytes)
                    ToolResult(call.callId, true, "edited ${ws.virtualOf(f)}: $count replacement" + (if (count == 1) "" else "s"),
                        attachment = FileAttachment(path = ws.virtualOf(f), name = f.name, updatedAt = System.currentTimeMillis()))
                }
                "append" -> {
                    val content = call.content
                    if (content.isNullOrBlank()) return ToolResult(call.callId, false, "action append needs content.")
                    if (ext == "pdf") return ToolResult(call.callId, false, "${call.path}: PDFs cannot be edited in place — create a new one with action create.")
                    if (ext != "docx") return ToolResult(call.callId, false, "${call.path}: only .docx documents support append. For plain files use edit_file.")
                    val f = ws.resolve(path)
                    if (!f.isFile) return ToolResult(call.callId, false, "${call.path}: no such file — use action create to make it first.")
                    val newBytes = appendDocx(f.readBytes(), content)
                    f.writeBytes(newBytes)
                    ToolResult(call.callId, true, "appended paragraphs to ${ws.virtualOf(f)}",
                        attachment = FileAttachment(path = ws.virtualOf(f), name = f.name, updatedAt = System.currentTimeMillis()))
                }
                else -> ToolResult(call.callId, false, "unknown document action \"${call.action}\" (create, replace, append).")
            }
        } catch (e: Exception) {
            throw if (e is WorkspaceException) e else WorkspaceException("document $action on $path failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ----------------------------------------------------------- markdown-ish blocks

    /** One content block: the text plus heading level (0-3) and bullet flag. */
    private data class Block(val text: String, val heading: Int = 0, val bullet: Boolean = false)

    /** Line begins: `#`, `##`, `###` headings; `- ` or `•` bullets; anything else is text. */
    private fun parseBlocks(content: String): List<Block> {
        val out = mutableListOf<Block>()
        content.lines().forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEach
            var heading = 0
            var text = trimmed
            while (heading < 3 && text.startsWith("#")) { heading++; text = text.removePrefix("#") }
            text = text.trimStart()
            var bullet = false
            when {
                text.startsWith("- ") -> { bullet = true; text = text.removePrefix("- ").trimStart() }
                text.startsWith("• ") -> { bullet = true; text = text.removePrefix("• ").trimStart() }
            }
            out += Block(text, heading, bullet)
        }
        return out
    }

    // ----------------------------------------------------------- docx writing

    /** Builds a minimal WordprocessingML package, valid in Word, LibreOffice and Google Docs. */
    internal fun buildDocx(content: String): ByteArray {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().newDocument()
        val root = doc.createElementNS(W_NS, "w:document")
        root.setAttribute("xmlns:w", W_NS)
        doc.appendChild(root)
        val body = doc.createElementNS(W_NS, "w:body")
        root.appendChild(body)
        for (block in parseBlocks(content)) body.appendChild(docxParagraph(doc, block))
        val documentXml = serialize(doc)

        val types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray(Charsets.UTF_8)
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""".toByteArray(Charsets.UTF_8)

        return zip(linkedMapOf(
            "[Content_Types].xml" to types,
            "_rels/.rels" to rels,
            "word/document.xml" to documentXml,
        ))
    }

    private const val BOLD = "b"
    private const val ITALIC = "i"
    private const val CODE = "c"

    private fun docxParagraph(doc: Document, b: Block): Element {
        val p = doc.createElementNS(W_NS, "w:p")
        if (b.bullet) {
            val pPr = doc.createElementNS(W_NS, "w:pPr")
            val ind = doc.createElementNS(W_NS, "w:ind")
            ind.setAttributeNS(W_NS, "w:left", "420")
            pPr.appendChild(ind)
            p.appendChild(pPr)
        }
        for ((text, style) in inlinePieces(b.text)) {
            if (text.isEmpty()) continue
            val r = doc.createElementNS(W_NS, "w:r")
            val rPr = doc.createElementNS(W_NS, "w:rPr")
            if (style == BOLD || b.heading > 0) rPr.appendChild(doc.createElementNS(W_NS, "w:b"))
            if (style == ITALIC) rPr.appendChild(doc.createElementNS(W_NS, "w:i"))
            if (style == CODE) {
                val fonts = doc.createElementNS(W_NS, "w:rFonts")
                fonts.setAttributeNS(W_NS, "w:ascii", "Courier New")
                fonts.setAttributeNS(W_NS, "w:hAnsi", "Courier New")
                rPr.appendChild(fonts)
            }
            val sz = doc.createElementNS(W_NS, "w:sz")
            sz.setAttributeNS(W_NS, "w:val", when (b.heading) { 1 -> "32"; 2 -> "28"; 3 -> "24"; else -> "22" })
            rPr.appendChild(sz)
            val t = doc.createElementNS(W_NS, "w:t")
            t.setAttribute("xml:space", "preserve")
            t.textContent = text
            r.appendChild(t)
            p.appendChild(r)
        }
        return p
    }

    /** Splits text on inline markers (**bold**, *italic*, `code`); each piece tagged with its style. */
    private fun inlinePieces(s: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var last = 0
        for (m in Regex("(\\*\\*[^*]+\\*\\*|\\*[^*]+\\*|`[^`]+`)").findAll(s)) {
            if (m.range.first > last) out += s.substring(last, m.range.first) to ""
            val token = m.value
            val style = when {
                token.startsWith("**") -> BOLD
                token.startsWith("`") -> CODE
                else -> ITALIC
            }
            val inner = token.trim('*', '`')
            out += inner to style
            last = m.range.last + 1
        }
        if (last < s.length) out += s.substring(last) to ""
        return out
    }

    // ----------------------------------------------------------- docx editing

    /**
     * Replaces occurrences of [oldText] inside the paragraphs of a .docx package. Matching is per
     * paragraph but across runs: text is mapped over w:t nodes, so a sentence split into differently
     * formatted runs still matches; the first affected run keeps its formatting.
     */
    private fun replaceDocx(zipBytes: ByteArray, oldText: String, newText: String, replaceAll: Boolean): Pair<ByteArray, Int> {
        val doc = dom(zipEntry(zipBytes, "word/document.xml") ?: throw WorkspaceException("not a Word .docx file"))
        val paragraphs = doc.getElementsByTagNameNS("*", "p")

        fun texts(): List<List<Element>> = (0 until paragraphs.length).mapNotNull { i ->
            val ts = (paragraphs.item(i) as Element).getElementsByTagNameNS("*", "t")
            (0 until ts.length).mapNotNull { ts.item(it) as? Element }.takeIf { it.isNotEmpty() }
        }

        // Count occurrences on untouched text so ambiguous old_text is rejected before any change.
        var total = 0
        for (nodes in texts()) total += occurrences(nodes.joinToString("") { it.textContent ?: "" }, oldText)
        if (total == 0) {
            throw WorkspaceException("old_text was not found in the document; read the extracted text and copy it exactly (matching is within one paragraph).")
        }
        if (total > 1 && !replaceAll) {
            throw WorkspaceException("old_text matches $total times in the document; add more context to make it unique, or set replace_all.")
        }

        val wanted = if (replaceAll) total else 1
        var done = 0
        for (nodes in texts()) {
            while (done < wanted) {
                val paragraph = nodes.joinToString("") { it.textContent ?: "" }
                val at = paragraph.indexOf(oldText)
                if (at < 0) break
                val end = at + oldText.length
                var offset = 0
                val range = nodes.map { n ->
                    val start = offset
                    offset += (n.textContent ?: "").length
                    Triple(n, start, offset)
                }.filter { (_, s, e) -> e > at && s < end }
                if (range.isEmpty()) break
                range.forEachIndexed { idx, (node, start, stop) ->
                    val localStart = maxOf(at, start) - start
                    val localEnd = minOf(end, stop) - start
                    val current = node.textContent ?: ""
                    node.textContent = when {
                        idx == 0 && stop >= end -> current.substring(0, localStart) + newText + current.substring(localEnd)
                        idx == 0 -> current.substring(0, localStart) + newText
                        stop >= end -> current.substring(localEnd)
                        else -> ""
                    }
                }
                done++
                if (!replaceAll) break
            }
        }
        return finalize(doc, zipBytes) to done
    }

    /** Number of non-overlapping occurrences of [needle] in [haystack]. */
    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var n = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            n++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return n
    }

    private fun finalize(doc: Document, zipBytes: ByteArray): ByteArray =
        rewriteZip(zipBytes, "word/document.xml", serialize(doc))

    private fun appendDocx(zipBytes: ByteArray, content: String): ByteArray {
        val doc = dom(zipEntry(zipBytes, "word/document.xml") ?: throw WorkspaceException("not a Word .docx file"))
        val body = doc.getElementsByTagNameNS("*", "body").item(0) as? Element
            ?: throw WorkspaceException("the document has no body to append to")
        for (block in parseBlocks(content)) body.appendChild(docxParagraph(doc, block))
        return rewriteZip(zipBytes, "word/document.xml", serialize(doc))
    }

    // ---------------------------------------------------------------- zip helpers

    private fun dom(xml: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun serialize(doc: Document): ByteArray {
        val tr = TransformerFactory.newInstance().newTransformer()
        tr.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        val out = ByteArrayOutputStream()
        tr.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    private fun zipEntry(zipBytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                if (e.name == name && !e.isDirectory) return z.readBytes()
                e = z.nextEntry
            }
        }
        return null
    }

    private fun zipEntry(file: File, name: String): ByteArray? = zipEntry(file.readBytes(), name)

    private fun zipEntries(file: File): LinkedHashMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream()).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return out
    }

    /** Replaces one entry's bytes, keeping every other entry verbatim (and in order). */
    private fun rewriteZip(zipBytes: ByteArray, name: String, newBytes: ByteArray): ByteArray {
        val entries = zipEntries(zipBytes)
        entries[name] = newBytes
        return zip(entries)
    }

    /** Replaces one entry's bytes, keeping every other entry verbatim (and in order). */
    private fun zipEntries(zipBytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return out
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- pdf writing

    /**
     * Builds a text PDF from the same markdown-ish content, deterministically hand-rolled
     * (no PDFBox needed to write: a minimal %PDF-1.4 file with standard Helvetica, WinAnsi
     * encoding, word-wrapped over A4 pages). Text extraction still goes through PDFBox.
     */
    internal fun buildPdf(content: String): ByteArray {
        data class Line(val font: String, val size: Float, val x: Float, val text: String)

        // Layout against A4 (595x842pt) with wrap measured in real Helvetica widths.
        val margin = 56f
        var y = 778f
        val lines = mutableListOf<Line>()
        fun draw(font: String, size: Float, x: Float, text: String) {
            lines += Line(font, size, x, text)
            y -= size * 1.42f
        }
        val width = 595f - 2 * margin
        for (b in parseBlocks(content)) {
            val size = when (b.heading) { 1 -> 17f; 2 -> 14f; 3 -> 12f; else -> 11f }
            val font = if (b.heading > 0) "F2" else "F1"
            val indent = if (b.bullet) 18f else 0f
            val text = if (b.bullet && b.heading == 0) "\u2022  " + b.text else b.text
            var line = StringBuilder()
            var lineWidth = 0f
            for (word in text.split(' ')) {
                val w = helveticaWidth(winAnsi(word)) * size
                val proposed = if (line.isEmpty()) w else lineWidth + helveticaWidth(winAnsi(" ")) * size + w
                if (line.isNotEmpty() && proposed > width - indent) {
                    draw(font, size, margin + indent, line.toString())
                    line = StringBuilder(word)
                    lineWidth = w
                } else {
                    if (line.isNotEmpty()) { line.append(' '); lineWidth += helveticaWidth(winAnsi(" ")) * size }
                    line.append(word)
                    lineWidth += w
                }
            }
            if (line.isNotEmpty()) draw(font, size, margin + indent, line.toString())
            if (b.heading > 0) y -= size * 0.5f
        }
        if (lines.isEmpty()) lines += Line("F1", 11f, margin, " ")

        // Paginate: split [lines] into pages.
        val pages = mutableListOf<List<Line>>()
        var pageLines = mutableListOf<Line>()
        var space = 778f
        for (line in lines) {
            if (space < 70f) { pages += pageLines; pageLines = mutableListOf(); space = 778f }
            pageLines += line
            space -= line.size * 1.42f
        }
        if (pageLines.isNotEmpty()) pages += pageLines

        // Object layout: 1 catalog, 2 pages, then per page [page, contents], then fonts F1, F2.
        val fontRegular = 3 + 2 * pages.size
        val fontBold = fontRegular + 1
        val objects = mutableListOf<String>()

        // Content streams first so page objects can reference them deterministically.
        val pageObjects = mutableListOf<String>()
        val contentObjects = mutableListOf<String>()
        for ((index, page) in pages.withIndex()) {
            val sb = StringBuilder()
            var pen = 778f
            for (line in page) {
                sb.append("BT /").append(line.font).append(' ').append(fmt(line.size)).append(
                    " Tf 1 0 0 1 ").append(fmt(line.x)).append(' ').append(fmt(pen - line.size)).append(
                    " Tm (").append(winAnsi(line.text).esc()) .append(") Tj ET\n")
                pen -= line.size * 1.42f
            }
            contentObjects += "<</Length ${sb.length}>>\nstream\n$sb\nendstream"
            pageObjects += "<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Resources<</Font<</F1 $fontRegular 0 R/F2 $fontBold 0 R>>>>/Contents ${3 + 2 * index + 1} 0 R>>"
        }

        val kids = pages.indices.joinToString(" ") { "${3 + 2 * it} 0 R" }
        objects += "<</Type/Catalog/Pages 2 0 R>>"
        objects += "<</Type/Pages/Kids[$kids]/Count ${pages.size}>>"
        objects += pageObjects
        objects += contentObjects
        objects += "<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>"
        objects += "<</Type/Font/Subtype/Type1/BaseFont/Helvetica-Bold/Encoding/WinAnsiEncoding>>"

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        var byteCount = out.length - 0
        var next = 1
        for (obj in objects) {
            byteCount = out.length
            out.append(next++).append(" 0 obj\n").append(obj).append("\nendobj\n")
            offsets += byteCount
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) out.append("%010d 00000 n \n".format(o))
        out.append("trailer\n<</Size ${objects.size + 1}/Root 1 0 R>>\nstartxref\n").append(xref).append("\n%%EOF\n")
        // The whole file is ASCII (WinAnsi = single bytes <= 0xFF).
        return out.toString().map { it.code.toByte() }.toByteArray()
    }

    private fun fmt(v: Float): String {
        val r = java.math.BigDecimal(v.toDouble()).setScale(2, java.math.RoundingMode.HALF_UP)
        return if (r.stripTrailingZeros().scale() <= 0) r.abs().stripTrailingZeros().toBigInteger().toString() else r.abs().toPlainString()
    }

    /** Text bytes for the stream: WinAnsiEncoding, '?' for anything it cannot represent. */
    private fun winAnsi(s: String): String {
        val special = mapOf(
            '\u2018' to 145.toChar(), '\u2019' to 146.toChar(), '\u201C' to 147.toChar(), '\u201D' to 148.toChar(),
            '\u2013' to 150.toChar(), '\u2014' to 151.toChar(), '\u2022' to 149.toChar(), '\u2026' to 133.toChar(),
            '\u20AC' to 128.toChar(),
        )
        return buildString {
            for (c in s) {
                val mapped = special[c]
                when {
                    mapped != null -> append(mapped)
                    c.code in 0x20..0x7E || c.code in 0xA0..0xFF -> append(c)
                    else -> append('?')
                }
            }
        }
    }

    private fun String.esc(): String =
        replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    /** Helvetica advance width in 1/1000 em for WinAnsi characters (default 556). */
    private fun helveticaWidth(s: String): Float {
        var total = 0f
        for (c in s) total += (HELVT_WIDTH.getOrNull(c.code) ?: 556f)
        return total / 1000f
    }

    /** Helvetica (Roman) widths for codes 32..126, from the standard AFM. */
    private val HELVT_WIDTH = FloatArray(127).also { w ->
        val widths = intArrayOf(
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, // 32-41
            389, 584, 278, 333, 278, 278, // 42-47
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, // 48-57
            278, 278, 584, 584, 584, 556, // 58-63
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, // 64-73
            500, 667, 556, 833, 722, 778, 667, 778, 722, 667, // 74-83
            611, 722, 667, 944, 667, 667, 611, // 84-90
            278, 278, 278, 469, 556, 333, // 91-96
            556, 556, 500, 556, 556, 278, 556, 556, 222, 222, // 97-106
            500, 222, 833, 556, 556, 556, 556, 333, 500, 278, // 107-116
            556, 500, 722, 500, 500, 500, // 117-122
            334, 260, 334, 584, // 123-126
        )
        widths.forEachIndexed { i, width -> w[32 + i] = width.toFloat() }
        w[32] = 278f
    }
}
