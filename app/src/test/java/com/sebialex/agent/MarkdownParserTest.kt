package com.sebialex.agent

import com.sebialex.agent.domain.markdown.DiffType
import com.sebialex.agent.domain.markdown.LineDiff
import com.sebialex.agent.domain.markdown.MarkdownParser
import com.sebialex.agent.domain.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private val doc = """
        # Title

        Intro paragraph with `code`.

        - [x] done
        - [ ] todo

        | A | B |
        | --- | ---: |
        | 1 | 2 |

        > quoted

        ```kotlin
        val x = 1
        ```
    """.trimIndent()

    @Test fun parsesBlocks() {
        val blocks = MarkdownParser.parse(doc)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf(true, false), items.map { it.checked })
        assertEquals(1, blocks.filterIsInstance<MdBlock.Table>().single().rows.size)
        assertTrue(blocks.any { it is MdBlock.Quote })
        assertEquals("val x = 1", blocks.filterIsInstance<MdBlock.Code>().single().code)
    }

    @Test fun togglesTask() {
        val item = MarkdownParser.parse(doc).filterIsInstance<MdBlock.ListItem>().last()
        val toggled = MarkdownParser.toggleTask(doc, item.line)
        assertTrue(toggled.contains("- [x] todo"))
    }

    @Test fun diffCountsLines() {
        val d = LineDiff.diff("a\nb\nc", "a\nc\nd")
        assertEquals(1, d.count { it.type == DiffType.REMOVED })
        assertEquals(1, d.count { it.type == DiffType.ADDED })
    }
}
