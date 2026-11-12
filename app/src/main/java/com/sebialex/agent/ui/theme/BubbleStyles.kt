package com.sebialex.agent.ui.theme

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * A user chat bubble look: a gradient plus one optional effect, drawn over the default surface
 * only when the user picks one (empty id = the plain surface, unchanged).
 */
data class BubbleStyle(
    val id: String,
    val label: String,
    val colors: List<Color>,
    /** sheen, glow, glass, neon, stripes or dots — see [BubbleStyles.effectModifier]. */
    val effect: String = "",
)

object BubbleStyles {
    private val NONE = BubbleStyle("", "Default", emptyList())

    private val AURORA = BubbleStyle("aurora", "Aurora", listOf(Color(0xFF2E3350), Color(0xFF4A3F7A), Color(0xFF2E5E6B)), effect = "sheen")
    private val SUNSET = BubbleStyle("sunset", "Sunset", listOf(Color(0xFF6D3352), Color(0xFFA34A3F), Color(0xFFC97B3D)), effect = "sheen")
    private val BLURPLE = BubbleStyle("blurple", "Blurple", listOf(Color(0xFF404BD8), Color(0xFF5865F2), Color(0xFF6E7BFF)))
    private val OCEAN_GLASS = BubbleStyle("ocean-glass", "Ocean glass", listOf(Color(0x663BA2E0), Color(0x662E6FD8)), effect = "glass")
    private val CANDY = BubbleStyle("candy", "Candy", listOf(Color(0xFFD96AA7), Color(0xFF9B6DFF)), effect = "sheen")
    private val EMBER = BubbleStyle("ember", "Ember", listOf(Color(0xFF7A2E2E), Color(0xFFB84A3A)))
    private val FOREST = BubbleStyle("forest", "Forest", listOf(Color(0xFF1F4A33), Color(0xFF2E6E4A)), effect = "sheen")
    private val CARAMEL = BubbleStyle("caramel", "Caramel", listOf(Color(0xFF8A5A2E), Color(0xFFC08A3E)), effect = "sheen")
    private val SLATE = BubbleStyle("slate", "Slate", listOf(Color(0xFF37414F), Color(0xFF4C5A6B)))
    private val GRAPE = BubbleStyle("grape", "Grape", listOf(Color(0xFF4A2470), Color(0xFF7A3FB0)), effect = "glow")
    private val NEON_PINK = BubbleStyle("neon-pink", "Neon pink", listOf(Color(0xFF31122A), Color(0xFF571B44)), effect = "neon")
    private val NEON_MINT = BubbleStyle("neon-mint", "Neon mint", listOf(Color(0xFF0F2E26), Color(0xFF175243)), effect = "neon")
    private val CYBER = BubbleStyle("cyber", "Cyber", listOf(Color(0xFF10141F), Color(0xFF1B2A4A)), effect = "stripes")
    private val DOT_MATRIX = BubbleStyle("dot-matrix", "Dot matrix", listOf(Color(0xFF2A2A31), Color(0xFF3C3C46)), effect = "dots")
    private val ICE = BubbleStyle("ice", "Ice", listOf(Color(0xFFDDEBF7), Color(0xFFC3DCF0)), effect = "glass")
    private val GOLD = BubbleStyle("gold", "Gold", listOf(Color(0xFF7A5A17), Color(0xFFB8860B), Color(0xFFD9A93A)), effect = "glow")

    val ALL = listOf(
        NONE, AURORA, SUNSET, BLURPLE, OCEAN_GLASS, CANDY, EMBER, FOREST, CARAMEL, SLATE, GRAPE,
        NEON_PINK, NEON_MINT, CYBER, DOT_MATRIX, ICE, GOLD,
    )

    fun byId(id: String): BubbleStyle = ALL.firstOrNull { it.id == id } ?: NONE

    /** The bubble's background brush; null for the default style (plain surface). */
    fun brush(s: BubbleStyle): Brush? = if (s.colors.isEmpty()) null else Brush.linearGradient(s.colors)

    /**
     * Text on the bubble: the style's colors are first composited over [backdrop] (the surface
     * the bubble sits on — translucent styles read very differently on dark and light themes),
     * then near-white or near-black is chosen, pushed to at least 4.5 contrast.
     */
    fun textColor(s: BubbleStyle, fallback: Color, backdrop: Color): Color {
        if (s.colors.isEmpty()) return fallback
        val solid = s.colors.map { lerp(backdrop, it, it.alpha) }
        val avg = Color(
            solid.sumOf { it.red.toDouble() / solid.size }.toFloat(),
            solid.sumOf { it.green.toDouble() / solid.size }.toFloat(),
            solid.sumOf { it.blue.toDouble() / solid.size }.toFloat(),
        )
        val white = Color(0xFFF4F2F0)
        val black = Color(0xFF141210)
        val base = if (Themes.contrast(white, avg) >= Themes.contrast(black, avg)) white else black
        var out = base
        var w = 0f
        while (Themes.contrast(out, avg) < 4.5f && w < 1f) {
            w = (w + 0.15f).coerceAtMost(1f)
            out = lerp(base, if (base == white) black else white, w)
        }
        return out
    }

    /** True when the style's average is light. */
    fun isLight(s: BubbleStyle): Boolean =
        s.colors.isNotEmpty() && s.colors.map { it.luminance() }.average() > 0.5f

    /**
     * The style's effect over an already-drawn gradient: sheen (a soft light band), glow (a
     * bright rim), glass (translucent with a hairline border and a vertical sheen), neon (a
     * glowing rim in the style's own color), stripes (diagonal hairlines) or dots (a dot grid).
     */
    fun effectModifier(s: BubbleStyle, shape: Shape): Modifier = when (s.effect) {
        "sheen" -> Modifier.drawWithContent {
            drawContent()
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.20f),
                    0.45f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
        "glow" -> Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), shape)
        "glass" -> Modifier
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.12f),
                        1f to Color.Transparent,
                    ),
                )
            }
        "neon" -> {
            val glow = s.colors.last()
            Modifier
                .shadow(10.dp, shape, ambientColor = glow, spotColor = glow, clip = false)
                .border(1.dp, glow.copy(alpha = 0.85f), shape)
                .drawWithContent {
                    drawContent()
                    drawRoundRect(
                        color = glow.copy(alpha = 0.25f),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
        }
        "stripes" -> Modifier.drawWithContent {
            drawContent()
            val step = 14.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    Color.White.copy(alpha = 0.07f),
                    start = Offset(x, size.height),
                    end = Offset(x + size.height, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
                x += step
            }
        }
        "dots" -> Modifier.drawWithContent {
            drawContent()
            val step = 12.dp.toPx()
            val r = 1.2.dp.toPx()
            var y = step / 2f
            while (y < size.height) {
                var x = step / 2f
                while (x < size.width) {
                    drawCircle(Color.White.copy(alpha = 0.10f), radius = r, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        else -> Modifier
    }
}
