package com.sebialex.agent

import com.sebialex.agent.domain.markdown.InlineParser
import com.sebialex.agent.domain.markdown.LatexParser
import com.sebialex.agent.domain.markdown.MarkdownParser
import com.sebialex.agent.domain.markdown.MathNode
import com.sebialex.agent.domain.markdown.MathText
import com.sebialex.agent.domain.markdown.MdBlock
import com.sebialex.agent.domain.markdown.MdInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Math in answers and documents: delimiters, inline vs display, and text that is not math. */
class MathParserTest {

    private fun inlineMath(text: String) = InlineParser.parse(text).filterIsInstance<MdInline.Math>()

    @Test fun dollarInline() {
        val m = inlineMath("The area is \$\\pi r^2\$ exactly.").single()
        assertEquals("\\pi r^2", m.tex)
        assertFalse(m.display)
    }

    @Test fun parenInline() {
        val m = inlineMath("so \\(a+b\\) holds").single()
        assertEquals("a+b", m.tex)
        assertFalse(m.display)
    }

    @Test fun displayDelimitersInsideAParagraph() {
        assertTrue(inlineMath("then \$\$x^2\$\$ and").single().display)
        assertTrue(inlineMath("then \\[x^2\\] and").single().display)
    }

    @Test fun pricesAreNotMath() {
        assertTrue(inlineMath("It costs \$5 and \$10 today.").isEmpty())
        assertTrue(inlineMath("Between \$ 3 and 4 \$ dollars").isEmpty())
        // The text keeps its dollar signs.
        val text = InlineParser.parse("It costs \$5 and \$10.").filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertEquals("It costs \$5 and \$10.", text)
    }

    @Test fun escapedDollarIsLiteral() {
        assertTrue(inlineMath("a \\\$x\\\$ b").isEmpty())
    }

    @Test fun unclosedInlineMathStaysText() {
        // An answer cut mid-formula by the typewriter shows plain text until the closing $ arrives.
        assertTrue(inlineMath("value \$\\frac{a}{b").isEmpty())
        assertTrue(inlineMath("value \\(x + ").isEmpty())
        assertTrue(inlineMath("value \$\$x + ").isEmpty())
        val text = InlineParser.parse("value \$\$x + ").filterIsInstance<MdInline.Text>().joinToString("") { it.text }
        assertEquals("value \$\$x + ", text)
    }

    @Test fun mathIsNotReadAsItalicOrCode() {
        val nodes = InlineParser.parse("\$a_1 * b_2\$")
        assertEquals(1, nodes.size)
        assertTrue(nodes.single() is MdInline.Math)
    }

    @Test fun displayBlockOneLine() {
        val blocks = MarkdownParser.parse("Before\n\n\$\$E = mc^2\$\$\n\nAfter")
        assertEquals(MdBlock.Math("E = mc^2"), blocks[1])
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test fun displayBlockMultiLine() {
        val blocks = MarkdownParser.parse("Solve:\n\\[\n\\int_0^1 x\\,dx\n= \\frac12\n\\]\nDone.")
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val math = blocks[1] as MdBlock.Math
        assertTrue(math.tex.startsWith("\\int_0^1"))
        assertTrue(math.tex.endsWith("\\frac12"))
        assertEquals(MdBlock.Paragraph("Done."), blocks[2])
    }

    @Test fun unclosedDisplayBlockIsParagraphText() {
        val blocks = MarkdownParser.parse("Result:\n\n\$\$\n\\sum_{i=1}^n i")
        assertTrue(blocks.none { it is MdBlock.Math })
        assertTrue(blocks.last() is MdBlock.Paragraph)
    }

    @Test fun mathInsideCodeIsLeftAlone() {
        val blocks = MarkdownParser.parse("```\n\$\$x\$\$\n```")
        assertEquals("\$\$x\$\$", (blocks.single() as MdBlock.Code).code)
        assertTrue(InlineParser.parse("`\$x\$`").single() is MdInline.Code)
    }

    @Test fun latexStructure() {
        val frac = LatexParser.parse("\\frac{a+b}{2}")
        assertTrue(frac is MathNode.Frac)
        val sum = LatexParser.parse("\\sum_{i=1}^{n} i") as MathNode.Row
        val op = sum.items.first() as MathNode.BigOp
        assertTrue(op.sub != null && op.sup != null)
        val root = LatexParser.parse("\\sqrt[3]{x}") as MathNode.Sqrt
        assertTrue(root.index != null)
        val matrix = LatexParser.parse("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}") as MathNode.Fenced
        assertEquals("(", matrix.open)
        assertEquals(2, (matrix.body as MathNode.Matrix).rows.size)
        assertEquals(2, (matrix.body as MathNode.Matrix).rows[1].size)
    }

    @Test fun malformedLatexNeverThrows() {
        listOf("\\frac{a", "}}x^", "\\left( x", "\\begin{matrix} 1 &", "^_^", "\\", "\\unknowncmd{x}").forEach {
            LatexParser.parse(it)
            MathText.plain(it)
        }
    }

    @Test fun plainText() {
        assertEquals("x²", MathText.plain("x^2"))
        assertEquals("a₁", MathText.plain("a_1"))
        assertEquals("α + β", MathText.plain("\\alpha + \\beta"))
        assertEquals("(a + b)/2", MathText.plain("\\frac{a+b}{2}"))
        assertEquals("ℝ", MathText.plain("\\mathbb{R}"))
        assertEquals("√x", MathText.plain("\\sqrt{x}"))
    }
}
