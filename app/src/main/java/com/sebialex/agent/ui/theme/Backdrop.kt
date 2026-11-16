package com.sebialex.agent.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The theme's backdrop: the gradient plus the current theme's decoration (stars, braille dots,
 * liquid blobs). Every screen background uses this, so decorated themes look the same app-wide.
 */
fun Modifier.themeBackdrop(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(Palette.Gradient))
    when (Palette.Decor) {
        "stars" -> drawStars()
        "braille" -> drawBraille()
        "liquid" -> drawLiquid()
    }
}

/** All decorations are pure functions of the canvas size and fixed seeds: identical every run. */
private fun DrawScope.drawStars() {
    val rnd = Random(20240117)
    val stars = List(110) {
        Star(x = rnd.nextFloat(), y = rnd.nextFloat(), r = 0.6f + rnd.nextFloat() * 1.6f, a = 0.12f + rnd.nextFloat() * 0.55f)
    }
    val bright = List(7) { Star(x = rnd.nextFloat(), y = rnd.nextFloat(), r = 1.8f + rnd.nextFloat(), a = 0.9f) }
    stars.forEach { s ->
        drawCircle(Color.White.copy(alpha = s.a), radius = s.r.dp.toPx(), center = Offset(s.x * size.width, s.y * size.height))
    }
    bright.forEach { s ->
        val c = Offset(s.x * size.width, s.y * size.height)
        drawCircle(Color(0xFFBFD0FF).copy(alpha = 0.10f), radius = s.r.dp.toPx() * 4f, center = c)
        drawCircle(Color.White.copy(alpha = s.a), radius = s.r.dp.toPx(), center = c)
    }
}

private data class Star(val x: Float, val y: Float, val r: Float, val a: Float)

private fun DrawScope.drawBraille() {
    // A braille cell is 2 columns x 3 rows of dots; which dots are raised spells the character.
    // Cells follow a fixed pseudo-random letter sequence so the wall of dots reads as texture,
    // not as a grid of identical tiles.
    val rnd = Random(19870101)
    val cell = 26.dp.toPx()
    val dot = 1.7f.dp.toPx()
    val gapX = cell / 2f
    val gapY = cell / 3f
    var row = 0
    var y = gapY
    while (y < size.height + gapY) {
        var col = 0
        var x = gapX
        while (x < size.width + gapX) {
            val pattern = rnd.nextInt(64)
            for (d in 0 until 6) {
                if (pattern and (1 shl d) == 0) continue
                val dx = if (d % 2 == 0) x else x + gapX
                val dy = y + (d / 2) * gapY
                drawCircle(Color.White.copy(alpha = 0.055f), radius = dot, center = Offset(dx, dy))
            }
            x += cell
        }
        y += cell
    }
}

private fun DrawScope.drawLiquid() {
    // Large soft color blobs at fixed relative positions: the "liquid" the glass surfaces sit on.
    val blobs = listOf(
        Liquid(0.12f, 0.10f, 0.55f, Color(0xFF2B5FD9)),
        Liquid(0.88f, 0.22f, 0.48f, Color(0xFF0E7490)),
        Liquid(0.30f, 0.62f, 0.52f, Color(0xFF7C3AED)),
        Liquid(0.78f, 0.86f, 0.45f, Color(0xFF0EA5E9)),
    )
    blobs.forEach { b ->
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(b.color.copy(alpha = 0.42f), Color.Transparent),
                center = Offset(b.x * size.width, b.y * size.height),
                radius = b.r * maxOf(size.width, size.height),
            ),
            radius = b.r * maxOf(size.width, size.height),
            center = Offset(b.x * size.width, b.y * size.height),
        )
    }
}

private data class Liquid(val x: Float, val y: Float, val r: Float, val color: Color)
