package com.sebialex.agent

import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.tools.DocumentTools
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The document layer: text extraction from Office/PDF formats and precise creation/editing
 * of .docx and .pdf — round-tripped through the real workspace checks.
 */
class DocumentToolsTest {

    private val tmp = Files.createTempDirectory("docs").toFile()

    private fun ws() = Workspace(tmp)

    /** Writes INTO the workspace so DocumentTools.run can resolve the path. */
    private fun write(name: String, bytes: ByteArray): java.io.File {
        val f = java.io.File(ws().root, name)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    private fun zipNames(bytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { names += e.name; e = z.nextEntry }
        }
        return names
    }



    // ---------------------------------------------------------------- extract

    @Test
    fun read_file_text_of_a_created_docx_roundtrips() {
        val bytes = DocumentToolsTest_createDocx(
            "My Report\n\nFirst paragraph worth of text with **bold** bits.\n\n## Section two\n\n- bullet one\n- bullet two\n\nTail paragraph.",
        )
        val f = write("My Report.docx", bytes)
        val text = DocumentTools.extract(f)
        assertTrue(text, text.startsWith("Word document (.docx)"))
        assertTrue(text, text.contains("My Report"))
        assertTrue(text, text.contains("First paragraph worth of text with bold bits."))
        assertTrue(text, text.contains("Section two"))
        assertTrue(text, text.contains("bullet one"))
        assertTrue(text, text.contains("Tail paragraph."))
    }

    @Test
    fun a_pptx_is_read_as_one_section_per_slide() {
        val slide = { id: Int, text: String ->
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="urn:pptx" xmlns:a="urn:drawing"><p:cSld><p:spTree><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:spTree></p:cSld></p:sld>""".toByteArray()
        }
        val f = write(
            "deck.pptx", DocumentToolsTest_zip(
                mapOf(
                    "[Content_Types].xml" to "<Types/>".toByteArray(),
                    "ppt/slides/slide10.xml" to slide(10, "later slide"),
                    "ppt/slides/slide2.xml" to slide(2, "early slide"),
                ),
            ),
        )
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("PowerPoint deck (.pptx)"))
        val early = text.indexOf("early slide")
        val later = text.indexOf("later slide")
        assertTrue(text, early in 0 until later) // numeric slide order, not lexicographic
    }

    @Test
    fun an_xlsx_resolves_shared_strings_and_renders_rows() {
        val f = write(
            "book.xlsx", DocumentToolsTest_zip(
                mapOf(
                    "[Content_Types].xml" to "<Types/>".toByteArray(),
                    "xl/sharedStrings.xml" to """<sst><si><t>Name</t></si><si><t>Cash Flow</t></si></sst>""".toByteArray(),
                    "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row><row r="2"><c><v>42</v></c><c t="inlineStr"><is><t>inline</t></is></c></row></sheetData></worksheet>""".toByteArray(),
                ),
            ),
        )
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("Name\tCash Flow"))
        assertTrue(text, text.contains("42\tinline"))
    }

    @Test
    fun rtf_control_words_are_stripped_but_text_stays() {
        val f = write("note.rtf", ("""{\rtf1\ansi{\fonttbl{\f0 Arial;}}{\info{\title ignored}}\b Bold \b0 text\par Second \tab line\'e9\par}""").toByteArray())
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("Rich text (.rtf)"))
        val body = text.substringAfter("extracted text:\n")
        assertTrue(text, body.contains("Bold"))
        assertTrue(text, body.contains("text"))
        assertTrue(text, body.contains("Second"))
        assertTrue(text, body.contains("line\u00e9"))
        assertTrue(text, !body.contains("fonttbl"))
        assertTrue(text, !body.contains("ignored"))
    }

    // ---------------------------------------------------------------- direct edit

    @Test
    fun replace_updates_one_paragraph_and_keeps_the_rest() {
        val f = write("report.docx", DocumentToolsTest_createDocx("Intro line.\n\nThe quarterly budget equals 100 units.\n\nClosing line."))
        val result = DocumentTools.run(ws(), ToolCall.Document(
            "c1", "report.docx", "replace", oldText = "The quarterly budget equals 100 units.", newText = "The quarterly budget equals 250 units.",
        ))
        assertTrue(result.ok)
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("The quarterly budget equals 250 units."))
        assertTrue(text, text.contains("Intro line."))
        assertTrue(text, text.contains("Closing line."))
        assertTrue(text, !text.contains("100 units"))
    }

    @Test
    fun replace_matches_text_split_across_formatted_runs() {
        // Created content has **bold** bits -> Word splits those sentences into separate runs.
        val f = write("runs.docx", DocumentToolsTest_createDocx("Value: **100** euro is due."))
        val result = DocumentTools.run(ws(), ToolCall.Document(
            "c1", "runs.docx", "replace", oldText = "Value: 100 euro is due.", newText = "Value: 250 euro is due.",
        ))
        assertTrue(result.ok)
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("Value: 250 euro is due."))
    }

    @Test
    fun ambiguous_replace_is_rejected_untouched() {
        val f = write("twice.docx", DocumentToolsTest_createDocx("same words again\n\nsame words again"))
        try {
            DocumentTools.run(ws(), ToolCall.Document("c1", "twice.docx", "replace", oldText = "same words", newText = "x"))
            org.junit.Assert.fail("expected an ambiguity error")
        } catch (e: com.sebialex.agent.data.workspace.WorkspaceException) {
            assertTrue(e.message, e.message!!.contains("matches 2 times"))
        }
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("same words again"))
    }

    @Test
    fun replace_all_hits_every_paragraph() {
        val f = write("all.docx", DocumentToolsTest_createDocx("acme widgets\n\nacme widgets again"))
        val result = DocumentTools.run(ws(), ToolCall.Document(
            "c1", "all.docx", "replace", oldText = "acme", newText = "ACME", replaceAll = true,
        ))
        assertTrue(result.ok)
        assertTrue(result.output, result.output.contains("2 replacements"))
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("ACME widgets"))
        assertTrue(text, text.contains("ACME widgets again"))
    }

    @Test
    fun append_adds_paragraphs_at_the_end() {
        val f = write("growing.docx", DocumentToolsTest_createDocx("First block."))
        val result = DocumentTools.run(ws(), ToolCall.Document("c1", "growing.docx", "append", content = "## Added heading\n\nAppended **bold** text."))
        assertTrue(result.ok)
        val text = DocumentTools.extract(f)
        assertTrue(text, text.contains("First block."))
        assertTrue(text, text.contains("Added heading"))
        assertTrue(text, text.contains("Appended bold text."))
    }

    @Test
    fun replacing_inside_a_pdf_names_the_limit() {
        write("doc.pdf", DocumentTools.buildPdf("unrelated"))
        val result = DocumentTools.run(ws(), ToolCall.Document(
            "c1", "doc.pdf", "replace", oldText = "a", newText = "b",
        ))
        assertTrue(result.output, !result.ok && result.output.contains("cannot be edited in place"))
    }

    // ---------------------------------------------------------------- pdf

    /**
     * PDF text extraction runs through PDFBox, which only loads its fonts from the APK's assets —
     * so created PDFs are verified structurally here (the extraction path is exercised on device).
     */
    @Test
    fun a_created_pdf_has_valid_structure_and_wrapped_pages() {
        val body = Array(60) { i -> "Line number ${i + 1} with a few more words to fill the page." }.joinToString("\n")
        val bytes = DocumentTools.buildPdf("Annual Report\n\n$body\n\n- first bullet\n- second bullet\n\nParenthetical (note) check")
        val text = bytes.decodeToString()
        assertTrue(text, text.contains("%PDF-1.4"))
        assertTrue(text, text.contains("(Annual Report) Tj"))
        assertTrue(text, text.contains("first bullet) Tj"))
        assertTrue(text, text.contains("second bullet) Tj"))
        assertTrue(text, text.contains("\\(note\\) check"))  // parentheses are escaped inside strings
        assertTrue(text, text.trimEnd().endsWith("%%EOF"))
        assertTrue(text, text.contains("/Type/Catalog"))
        assertTrue(text, text.contains("/BaseFont/Helvetica-Bold"))
        // 60+ wrapped lines at ~16pt spacing must have broken onto more than one A4 page.
        assertTrue(text, Regex("/Count ([2-9])").containsMatchIn(text))
    }
}

private fun DocumentToolsTest_createDocx(content: String): ByteArray = DocumentTools.buildDocx(content)

private fun DocumentToolsTest_zip(entries: Map<String, ByteArray>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { z ->
        for ((name, bytes) in entries) {
            z.putNextEntry(java.util.zip.ZipEntry(name))
            z.write(bytes)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}
