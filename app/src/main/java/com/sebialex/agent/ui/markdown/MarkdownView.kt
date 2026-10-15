package com.sebialex.agent.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebialex.agent.R
import com.sebialex.agent.domain.markdown.Align
import com.sebialex.agent.domain.markdown.InlineParser
import com.sebialex.agent.domain.markdown.MarkdownParser
import com.sebialex.agent.domain.markdown.MdBlock
import com.sebialex.agent.domain.markdown.MdInline
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Mono
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette

/**
 * Converts inline Markdown to an AnnotatedString with clickable links (handled by LocalUriHandler).
 * Inline math (`$…$`, `\(…\)`) becomes styled runs in the same string.
 */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    fun emit(nodes: List<MdInline>) {
        nodes.forEach { n ->
            when (n) {
                is MdInline.Text -> append(n.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)) { emit(n.children) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { emit(n.children) }
                is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { emit(n.children) }
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = Mono, background = Palette.SurfaceHighest, color = Palette.TextPrimary)) {
                    append("\u2009"); append(n.text); append("\u2009")
                }
                is MdInline.Link -> withLink(
                    LinkAnnotation.Url(
                        n.url,
                        TextLinkStyles(style = SpanStyle(color = Palette.Link, textDecoration = TextDecoration.Underline)),
                    ),
                ) { emit(n.children) }
                // Inline math (and $$…$$ written mid-sentence) flows with the words; see MathView.
                is MdInline.Math -> appendMath(n.tex)
            }
        }
    }
    emit(InlineParser.parse(text))
}

/**
 * Renders Markdown blocks. [compact] is used for chat answers; the reader uses the full scale.
 * [onToggleTask] makes checkboxes interactive (source line index).
 */
@Composable
fun MarkdownContent(
    source: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onToggleTask: ((Int) -> Unit)? = null,
) {
    val blocks = remember(source) { MarkdownParser.parse(source) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
        blocks.forEach { MarkdownBlockView(it, compact, onToggleTask) }
    }
}

@Composable
fun MarkdownBlockView(block: MdBlock, compact: Boolean, onToggleTask: ((Int) -> Unit)?) {
    val body = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyLarge
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium
                2 -> if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize)
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                inlineMarkdown(block.text), style = style, color = Palette.TextPrimary,
                modifier = Modifier.padding(top = if (block.level <= 2 && !compact) 10.dp else 2.dp),
            )
        }
        is MdBlock.Paragraph -> Text(inlineMarkdown(block.text), style = body, color = Palette.TextPrimary)
        is MdBlock.Code -> CodeBlock(block.language, block.code)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(Palette.TextTertiary))
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEach { inner ->
                    if (inner is MdBlock.Paragraph) Text(inlineMarkdown(inner.text), style = body, color = Palette.TextSecondary)
                    else MarkdownBlockView(inner, compact, onToggleTask)
                }
            }
        }
        is MdBlock.ListItem -> ListItemView(block, body, onToggleTask)
        is MdBlock.Table -> TableView(block)
        is MdBlock.Math -> MathBlock(block.tex, fontSize = if (compact) 18.sp else 20.sp)
        MdBlock.Rule -> Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Palette.SurfaceHighest),
        )
    }
}

@Composable
private fun ListItemView(item: MdBlock.ListItem, style: TextStyle, onToggleTask: ((Int) -> Unit)?) {
    Row(Modifier.padding(start = (item.depth * 18).dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(26.dp).height(25.dp), contentAlignment = Alignment.CenterStart) {
            when {
                item.checked != null -> AppIcon(
                    if (item.checked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox,
                    contentDescription = if (item.checked) "Done" else "Not done",
                    tint = if (item.checked) Palette.TextSecondary else Palette.IconMuted,
                    size = 19.dp,
                    modifier = if (onToggleTask != null) Modifier.clip(RoundedCornerShape(4.dp)).clickable { onToggleTask(item.line) } else Modifier,
                )
                item.ordered -> Text("${item.number}.", style = style, color = Palette.TextSecondary)
                else -> Box(Modifier.padding(start = 6.dp).size(5.dp).clip(CircleShape).background(Palette.TextSecondary))
            }
        }
        Text(
            inlineMarkdown(item.text),
            style = style,
            color = if (item.checked == true) Palette.TextTertiary else Palette.TextPrimary,
            textDecoration = if (item.checked == true) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.CodeBackground),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(language.ifEmpty { "text" }, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.weight(1f))
            QuietIconButton(R.drawable.ic_copy, "Copy code", onClick = { clipboard.setText(AnnotatedString(code)) }, tint = Palette.IconMuted, size = 36.dp, iconSize = 16.dp)
        }
        SelectionContainer {
            Text(
                code,
                style = MonoSmall,
                color = Palette.TextPrimary,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun TableView(table: MdBlock.Table) {
    val widths = remember(table) {
        table.header.indices.map { c ->
            val longest = (listOf(table.header[c]) + table.rows.map { it.getOrElse(c) { "" } }).maxOf { it.length }
            (longest * 8 + 28).coerceIn(84, 260)
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(table.header, widths, table.align, header = true)
            table.rows.forEachIndexed { i, r ->
                TableRow(r, widths, table.align, header = false, shaded = i % 2 == 1)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, widths: List<Int>, align: List<Align>, header: Boolean, shaded: Boolean = false) {
    Row(Modifier.background(if (header) Palette.SurfaceHigh else if (shaded) Palette.Ink.copy(alpha = 0.35f) else Palette.Surface)) {
        cells.forEachIndexed { i, cell ->
            Text(
                inlineMarkdown(cell),
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                color = if (header) Palette.TextPrimary else Palette.TextSecondary,
                textAlign = when (align.getOrElse(i) { Align.START }) {
                    Align.START -> TextAlign.Start
                    Align.CENTER -> TextAlign.Center
                    Align.END -> TextAlign.End
                },
                modifier = Modifier.width(widths.getOrElse(i) { 120 }.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}
