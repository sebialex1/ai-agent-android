package com.sebialex.agent.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sebialex.agent.domain.markdown.AccentMark
import com.sebialex.agent.domain.markdown.LatexParser
import com.sebialex.agent.domain.markdown.MathNode
import com.sebialex.agent.domain.markdown.MathText
import com.sebialex.agent.domain.markdown.SymKind
import com.sebialex.agent.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Math rendering, all native Compose: no WebView, no bundled script, works offline and costs no
 * more than text in a long chat.
 *
 * - Inline math (`$…$`, `\(…\)`) becomes styled text runs inside the paragraph's own Text
 *   ([appendMath]): it wraps, selects and copies with the words around it.
 * - Display math (`$$…$$`, `\[…\]`) is laid out as a real formula ([MathBlock]): stacked
 *   fractions, drawn radicals and growing delimiters, limits above and below big operators,
 *   matrices; centered, and scrolling sideways when wider than the answer.
 */

/** Appends inline math as runs: serif, italic variables, raised / lowered scripts. */
fun AnnotatedString.Builder.appendMath(tex: String) {
    MathText.runs(LatexParser.parse(tex)).forEach { r ->
        val level = abs(r.shift)
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Serif,
                fontStyle = if (r.italic) FontStyle.Italic else null,
                fontSize = if (level == 0) TextUnit.Unspecified else (0.72f.pow(level.coerceAtMost(2))).em,
                baselineShift = when {
                    r.shift > 0 -> BaselineShift(0.42f * level)
                    r.shift < 0 -> BaselineShift(-0.22f * level)
                    else -> null
                },
            ),
        ) { append(r.text) }
    }
}

/** A display formula, centered; wider than the answer it scrolls sideways instead of wrapping. */
@Composable
fun MathBlock(tex: String, modifier: Modifier = Modifier, fontSize: TextUnit = 19.sp) {
    val node = remember(tex) { LatexParser.parse(tex) }
    Box(modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)) {
            MathNodeView(node, MathStyle(fontSize, Palette.TextPrimary, display = true, level = 0))
        }
    }
}

/** Size and mode of one part of a formula. [level] counts script nesting (0 = main line). */
private data class MathStyle(val fontSize: TextUnit, val color: Color, val display: Boolean, val level: Int) {
    /** One script level down: smaller, never below half the main size. */
    fun script(): MathStyle = copy(fontSize = (fontSize.value * if (level >= 2) 1f else 0.7f).sp, display = false, level = level + 1)
    /** Numerator / denominator: same size in display math, slightly smaller inline. */
    fun fraction(): MathStyle = if (display) copy(display = false) else copy(fontSize = (fontSize.value * 0.8f).sp, level = level + 1)
}

@Composable
private fun MathNodeView(node: MathNode, style: MathStyle, modifier: Modifier = Modifier) {
    when (node) {
        is MathNode.Row -> Row(modifier) {
            node.items.forEach { MathNodeView(it, style, Modifier.alignByBaseline()) }
        }
        is MathNode.Sym -> SymView(node, style, modifier)
        is MathNode.Space -> Spacer(modifier.width(with(LocalDensity.current) { (style.fontSize.toPx() * node.em).toDp() }))
        is MathNode.Scripts -> ScriptsLayout(
            base = { MathNodeView(node.base, style) },
            sup = node.sup?.let { { MathNodeView(it, style.script()) } },
            sub = node.sub?.let { { MathNodeView(it, style.script()) } },
            style = style, modifier = modifier,
        )
        is MathNode.Frac -> FracLayout(node, style, modifier)
        is MathNode.Sqrt -> SqrtLayout(node, style, modifier)
        is MathNode.BigOp -> BigOpView(node, style, modifier)
        is MathNode.Accent -> AccentLayout(node, style, modifier)
        is MathNode.Fenced -> FencedLayout(node, style, modifier)
        is MathNode.Matrix -> MatrixLayout(node, style, modifier)
    }
}

private fun textStyle(style: MathStyle, italic: Boolean = false, scale: Float = 1f) = TextStyle(
    color = style.color,
    fontSize = (style.fontSize.value * scale).sp,
    fontFamily = FontFamily.Serif,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = (style.fontSize.value * scale * 1.18f).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
private fun SymView(sym: MathNode.Sym, style: MathStyle, modifier: Modifier) {
    val pad = with(LocalDensity.current) {
        // Relations and binary operators breathe on both sides on the main line, less in scripts.
        if (sym.kind == SymKind.OPERATOR) (style.fontSize.toPx() * if (style.level == 0) 0.24f else 0.08f).toDp() else 0.dp
    }
    BasicText(
        sym.text,
        style = textStyle(style, italic = sym.kind == SymKind.VARIABLE),
        softWrap = false,
        modifier = modifier.padding(horizontal = pad),
    )
}

private fun Placeable.baseline(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height

/** Base with a superscript and/or subscript beside it; the base keeps the baseline. */
@Composable
private fun ScriptsLayout(
    base: @Composable () -> Unit,
    sup: (@Composable () -> Unit)?,
    sub: (@Composable () -> Unit)?,
    style: MathStyle,
    modifier: Modifier,
) {
    Layout(
        content = {
            Box(Modifier.layoutId("base")) { base() }
            if (sup != null) Box(Modifier.layoutId("sup")) { sup() }
            if (sub != null) Box(Modifier.layoutId("sub")) { sub() }
        },
        modifier = modifier,
    ) { measurables, _ ->
        val loose = Constraints()
        val b = measurables.first { it.layoutId == "base" }.measure(loose)
        val p = measurables.firstOrNull { it.layoutId == "sup" }?.measure(loose)
        val s = measurables.firstOrNull { it.layoutId == "sub" }?.measure(loose)
        val em = style.fontSize.toPx()
        val bb = b.baseline()
        val supTop = p?.let { bb - (0.42f * em).roundToInt() - it.baseline() }
        var subTop = s?.let { bb + (0.24f * em).roundToInt() - it.baseline() }
        if (p != null && s != null && supTop != null && subTop != null) {
            subTop = max(subTop, supTop + p.height - (0.12f * em).roundToInt())
        }
        val top = min(0, min(supTop ?: 0, subTop ?: 0))
        val bottom = max(b.height, max(supTop?.plus(p!!.height) ?: 0, subTop?.plus(s!!.height) ?: 0))
        val gap = (0.04f * em).roundToInt()
        val width = b.width + gap + max(p?.width ?: 0, s?.width ?: 0)
        layout(width, bottom - top, mapOf(FirstBaseline to bb - top)) {
            b.placeRelative(0, -top)
            p?.placeRelative(b.width + gap, supTop!! - top)
            s?.placeRelative(b.width + gap, subTop!! - top)
        }
    }
}

/** Numerator over denominator with a rule between; the rule sits on the math axis. */
@Composable
private fun FracLayout(frac: MathNode.Frac, style: MathStyle, modifier: Modifier) {
    val inner = style.fraction()
    Layout(
        content = {
            Box(Modifier.layoutId("num")) { MathNodeView(frac.num, inner) }
            Box(Modifier.layoutId("bar").background(if (frac.bar) style.color else Color.Transparent))
            Box(Modifier.layoutId("den")) { MathNodeView(frac.den, inner) }
        },
        modifier = modifier,
    ) { measurables, _ ->
        val em = style.fontSize.toPx()
        val n = measurables.first { it.layoutId == "num" }.measure(Constraints())
        val d = measurables.first { it.layoutId == "den" }.measure(Constraints())
        val side = (0.12f * em).roundToInt()
        val width = max(n.width, d.width) + 2 * side
        val thick = max(1, (0.055f * em).roundToInt())
        val bar = measurables.first { it.layoutId == "bar" }.measure(Constraints.fixed(width, thick))
        val gap = (0.12f * em).roundToInt()
        val barY = n.height + gap
        val denY = barY + thick + gap
        val baseline = barY + (0.28f * em).roundToInt()
        layout(width, denY + d.height, mapOf(FirstBaseline to baseline)) {
            n.placeRelative((width - n.width) / 2, 0)
            bar.placeRelative(0, barY)
            d.placeRelative((width - d.width) / 2, denY)
        }
    }
}

/** A drawn radical sign that grows with its body, with an optional index in its crook. */
@Composable
private fun SqrtLayout(sqrt: MathNode.Sqrt, style: MathStyle, modifier: Modifier) {
    val geometry = remember { FloatArray(3) } // radical start x, radical width, top padding
    val color = style.color
    Layout(
        content = {
            Box(Modifier.layoutId("body")) { MathNodeView(sqrt.body, style) }
            if (sqrt.index != null) Box(Modifier.layoutId("index")) { MathNodeView(sqrt.index, style.script().script()) }
        },
        modifier = modifier.drawBehind {
            val (x0, rw, pad) = Triple(geometry[0], geometry[1], geometry[2])
            val h = size.height
            val stroke = max(1f, style.fontSize.toPx() * 0.055f)
            val path = Path().apply {
                moveTo(x0, h * 0.62f)
                lineTo(x0 + rw * 0.22f, h * 0.54f)
                lineTo(x0 + rw * 0.52f, h - stroke)
                lineTo(x0 + rw, pad * 0.45f)
                lineTo(size.width, pad * 0.45f)
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        },
    ) { measurables, _ ->
        val em = style.fontSize.toPx()
        val body = measurables.first { it.layoutId == "body" }.measure(Constraints())
        val index = measurables.firstOrNull { it.layoutId == "index" }?.measure(Constraints())
        val pad = (0.22f * em).roundToInt()
        val rw = max((0.55f * em).roundToInt(), (body.height * 0.18f).roundToInt())
        val x0 = max(0, (index?.width ?: 0) - (rw * 0.45f).roundToInt())
        geometry[0] = x0.toFloat(); geometry[1] = rw.toFloat(); geometry[2] = pad.toFloat()
        val height = body.height + pad
        val width = x0 + rw + body.width + (0.08f * em).roundToInt()
        layout(width, height, mapOf(FirstBaseline to body.baseline() + pad)) {
            body.placeRelative(x0 + rw, pad)
            index?.placeRelative(0, (height * 0.5f).roundToInt() - index.height)
        }
    }
}

/** ∑ ∏ ∫ lim …: in display math the limits stack above and below; inline they sit beside. */
@Composable
private fun BigOpView(op: MathNode.BigOp, style: MathStyle, modifier: Modifier) {
    val isWord = op.symbol.first().isLetter()
    val scale = when {
        isWord -> 1f
        style.display -> if (op.limits) 1.45f else 1.3f
        else -> 1.1f
    }
    val symbol: @Composable () -> Unit = {
        BasicText(op.symbol, style = textStyle(style, scale = scale), softWrap = false)
    }
    if (!(style.display && op.limits) || (op.sub == null && op.sup == null)) {
        if (op.sub == null && op.sup == null) {
            Box(modifier.padding(end = if (isWord) 3.dp else 1.dp)) { symbol() }
            return
        }
        ScriptsLayout(
            base = symbol,
            sup = op.sup?.let { { MathNodeView(it, style.script()) } },
            sub = op.sub?.let { { MathNodeView(it, style.script()) } },
            style = style, modifier = modifier.padding(end = 2.dp),
        )
        return
    }
    Layout(
        content = {
            Box(Modifier.layoutId("op")) { symbol() }
            op.sup?.let { Box(Modifier.layoutId("sup")) { MathNodeView(it, style.script()) } }
            op.sub?.let { Box(Modifier.layoutId("sub")) { MathNodeView(it, style.script()) } }
        },
        modifier = modifier.padding(horizontal = 2.dp),
    ) { measurables, _ ->
        val em = style.fontSize.toPx()
        val o = measurables.first { it.layoutId == "op" }.measure(Constraints())
        val p = measurables.firstOrNull { it.layoutId == "sup" }?.measure(Constraints())
        val s = measurables.firstOrNull { it.layoutId == "sub" }?.measure(Constraints())
        val gap = (0.06f * em).roundToInt()
        val width = maxOf(o.width, p?.width ?: 0, s?.width ?: 0)
        val opY = (p?.height ?: 0) + if (p != null) gap else 0
        val subY = opY + o.height + gap
        val height = if (s != null) subY + s.height else opY + o.height
        // The big sign is centered on the axis: its baseline sits a little below the text's.
        val baseline = opY + o.baseline() - if (isWord) 0 else (0.12f * em).roundToInt()
        layout(width, height, mapOf(FirstBaseline to baseline)) {
            p?.placeRelative((width - p.width) / 2, 0)
            o.placeRelative((width - o.width) / 2, opY)
            s?.placeRelative((width - s.width) / 2, subY)
        }
    }
}

/** A mark over its body (hat, arrow, dot, tilde) or a drawn bar over / under it. */
@Composable
private fun AccentLayout(accent: MathNode.Accent, style: MathStyle, modifier: Modifier) {
    val drawn = accent.mark == AccentMark.BAR || accent.mark == AccentMark.UNDERLINE
    val glyph = when (accent.mark) {
        AccentMark.HAT -> "\u02C6"
        AccentMark.VEC -> "\u2192"
        AccentMark.DOT -> "\u02D9"
        AccentMark.DDOT -> "\u00A8"
        AccentMark.TILDE -> "\u02DC"
        else -> ""
    }
    Layout(
        content = {
            Box(Modifier.layoutId("body")) { MathNodeView(accent.body, style) }
            if (drawn) Box(Modifier.layoutId("mark").background(style.color))
            else Box(Modifier.layoutId("mark")) { BasicText(glyph, style = textStyle(style, scale = if (accent.mark == AccentMark.VEC) 0.7f else 0.9f), softWrap = false) }
        },
        modifier = modifier,
    ) { measurables, _ ->
        val em = style.fontSize.toPx()
        val body = measurables.first { it.layoutId == "body" }.measure(Constraints())
        val thick = max(1, (0.05f * em).roundToInt())
        val mark = measurables.first { it.layoutId == "mark" }.measure(if (drawn) Constraints.fixed(body.width, thick) else Constraints())
        val width = max(body.width, mark.width)
        when {
            accent.mark.under -> {
                val y = body.height + (0.04f * em).roundToInt()
                layout(width, y + thick, mapOf(FirstBaseline to body.baseline())) {
                    body.placeRelative((width - body.width) / 2, 0); mark.placeRelative(0, y)
                }
            }
            drawn -> {
                val bodyY = thick + (0.06f * em).roundToInt()
                layout(width, bodyY + body.height, mapOf(FirstBaseline to bodyY + body.baseline())) {
                    mark.placeRelative(0, 0); body.placeRelative((width - body.width) / 2, bodyY)
                }
            }
            else -> {
                // Glyph marks carry their own line box: pull the body up under the visible mark.
                val bodyY = max(0, (mark.height * 0.42f).roundToInt())
                layout(width, bodyY + body.height, mapOf(FirstBaseline to bodyY + body.baseline())) {
                    mark.placeRelative((width - mark.width) / 2, 0); body.placeRelative((width - body.width) / 2, bodyY)
                }
            }
        }
    }
}

/** Delimiters drawn to the full height of their body: ( ) [ ] { } | ‖ ⟨ ⟩ ⌊ ⌋ ⌈ ⌉. */
@Composable
private fun FencedLayout(fenced: MathNode.Fenced, style: MathStyle, modifier: Modifier) {
    Layout(
        content = {
            if (fenced.open.isNotEmpty()) Delimiter(fenced.open, style, Modifier.layoutId("open"))
            Box(Modifier.layoutId("body")) { MathNodeView(fenced.body, style) }
            if (fenced.close.isNotEmpty()) Delimiter(fenced.close, style, Modifier.layoutId("close"))
        },
        modifier = modifier,
    ) { measurables, _ ->
        val em = style.fontSize.toPx()
        val body = measurables.first { it.layoutId == "body" }.measure(Constraints())
        val h = max(body.height, (1.1f * em).roundToInt())
        val w = (0.38f * em).roundToInt()
        val open = measurables.firstOrNull { it.layoutId == "open" }?.measure(Constraints.fixed(w, h))
        val close = measurables.firstOrNull { it.layoutId == "close" }?.measure(Constraints.fixed(w, h))
        val bodyY = (h - body.height) / 2
        val width = (open?.width ?: 0) + body.width + (close?.width ?: 0)
        layout(width, h, mapOf(FirstBaseline to bodyY + body.baseline())) {
            open?.placeRelative(0, 0)
            body.placeRelative(open?.width ?: 0, bodyY)
            close?.placeRelative((open?.width ?: 0) + body.width, 0)
        }
    }
}

@Composable
private fun Delimiter(kind: String, style: MathStyle, modifier: Modifier) {
    val color = style.color
    Box(
        modifier.drawBehind {
            val stroke = max(1f, style.fontSize.toPx() * 0.06f)
            val w = size.width
            val h = size.height
            val l = w * 0.25f
            val r = w * 0.75f
            val s = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(a: Offset, b: Offset) = drawLine(color, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
            when (kind) {
                "(" -> drawPath(Path().apply { moveTo(r, stroke); quadraticTo(l - w * 0.15f, h / 2, r, h - stroke) }, color, style = s)
                ")" -> drawPath(Path().apply { moveTo(l, stroke); quadraticTo(r + w * 0.15f, h / 2, l, h - stroke) }, color, style = s)
                "[", "\u230A", "\u2308" -> {
                    line(Offset(l, stroke), Offset(l, h - stroke))
                    if (kind != "\u230A") line(Offset(l, stroke), Offset(r, stroke))
                    if (kind != "\u2308") line(Offset(l, h - stroke), Offset(r, h - stroke))
                }
                "]", "\u230B", "\u2309" -> {
                    line(Offset(r, stroke), Offset(r, h - stroke))
                    if (kind != "\u230B") line(Offset(l, stroke), Offset(r, stroke))
                    if (kind != "\u2309") line(Offset(l, h - stroke), Offset(r, h - stroke))
                }
                "|" -> line(Offset(w / 2, stroke), Offset(w / 2, h - stroke))
                "\u2016" -> { line(Offset(w * 0.35f, stroke), Offset(w * 0.35f, h - stroke)); line(Offset(w * 0.65f, stroke), Offset(w * 0.65f, h - stroke)) }
                "\u27E8" -> drawPath(Path().apply { moveTo(r, stroke); lineTo(l, h / 2); lineTo(r, h - stroke) }, color, style = s)
                "\u27E9" -> drawPath(Path().apply { moveTo(l, stroke); lineTo(r, h / 2); lineTo(l, h - stroke) }, color, style = s)
                "{" -> drawPath(Path().apply {
                    val m = w * 0.5f
                    moveTo(r, stroke)
                    quadraticTo(m, stroke, m, h * 0.15f); lineTo(m, h * 0.38f)
                    quadraticTo(m, h * 0.5f, l - w * 0.05f, h * 0.5f)
                    quadraticTo(m, h * 0.5f, m, h * 0.62f); lineTo(m, h * 0.85f)
                    quadraticTo(m, h - stroke, r, h - stroke)
                }, color, style = s)
                "}" -> drawPath(Path().apply {
                    val m = w * 0.5f
                    moveTo(l, stroke)
                    quadraticTo(m, stroke, m, h * 0.15f); lineTo(m, h * 0.38f)
                    quadraticTo(m, h * 0.5f, r + w * 0.05f, h * 0.5f)
                    quadraticTo(m, h * 0.5f, m, h * 0.62f); lineTo(m, h * 0.85f)
                    quadraticTo(m, h - stroke, l, h - stroke)
                }, color, style = s)
            }
        },
    )
}

/** Matrix / cases / aligned grid: columns as wide as their widest cell, rows on one baseline. */
@Composable
private fun MatrixLayout(matrix: MathNode.Matrix, style: MathStyle, modifier: Modifier) {
    val cols = matrix.rows.maxOfOrNull { it.size } ?: 0
    Layout(
        content = {
            matrix.rows.forEach { row -> row.forEach { cell -> Box { MathNodeView(cell, style.copy(display = false)) } } }
        },
        modifier = modifier.padding(horizontal = 2.dp),
    ) { measurables, _ ->
        val em = style.fontSize.toPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        var k = 0
        val grid = matrix.rows.map { row -> row.map { placeables[k++] } }
        val colW = IntArray(cols)
        grid.forEach { row -> row.forEachIndexed { c, p -> colW[c] = max(colW[c], p.width) } }
        val ascent = grid.map { row -> row.maxOfOrNull { it.baseline() } ?: (0.8f * em).roundToInt() }
        val descent = grid.map { row -> row.maxOfOrNull { it.height - it.baseline() } ?: (0.3f * em).roundToInt() }
        val colGap = ((if (matrix.leftAligned) 1.0f else 0.8f) * em).roundToInt()
        val rowGap = (0.28f * em).roundToInt()
        val width = colW.sum() + colGap * max(0, cols - 1)
        val rowY = IntArray(grid.size)
        var y = 0
        grid.indices.forEach { r -> rowY[r] = y; y += ascent[r] + descent[r] + if (r < grid.lastIndex) rowGap else 0 }
        val height = max(y, 1)
        // The grid is centered on the math axis of the surrounding line.
        val baseline = height / 2 + (0.28f * em).roundToInt()
        layout(width, height, mapOf(FirstBaseline to baseline)) {
            grid.forEachIndexed { r, row ->
                var x = 0
                row.forEachIndexed { c, p ->
                    val dx = if (matrix.leftAligned) 0 else (colW[c] - p.width) / 2
                    p.placeRelative(x + dx, rowY[r] + ascent[r] - p.baseline())
                    x += colW[c] + colGap
                }
            }
        }
    }
}
