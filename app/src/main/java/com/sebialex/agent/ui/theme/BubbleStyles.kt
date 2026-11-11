package com.sebialex.agent.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A user chat bubble look: a gradient plus one optional effect, drawn over the default surface
 * only when the user picks one (empty id = the plain surface, unchanged).
 */
data class BubbleStyle(
    val id: String,
    val label: String,
    val colors: List<Color>,
    /** sheen: a soft light band; glow: a bright rim; glass: translucent with a hairline border. */
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

    val ALL = listOf(NONE, AURORA, SUNSET, BLURPLE, OCEAN_GLASS, CANDY, EMBER, FOREST, CARAMEL, SLATE, GRAPE)

    fun byId(id: String): BubbleStyle = ALL.firstOrNull { it.id == id } ?: NONE

    /** The bubble's background brush; null for the default style (plain surface). */
    fun brush(s: BubbleStyle): Brush? = if (s.colors.isEmpty()) null else Brush.linearGradient(s.colors)

    /**
     * Text on the bubble: near-white or near-black, whichever reads on the gradient's average,
     * then pushed to at least 4.5 contrast so every style keeps its text readable.
     */
    fun textColor(s: BubbleStyle, fallback: Color): Color {
        if (s.colors.isEmpty()) return fallback
        val avg = Color(
            s.colors.sumOf { it.red.toDouble() / s.colors.size }.toFloat(),
            s.colors.sumOf { it.green.toDouble() / s.colors.size }.toFloat(),
            s.colors.sumOf { it.blue.toDouble() / s.colors.size }.toFloat(),
        )
        val white = Color(0xFFF4F2F0)
        val black = Color(0xFF141210)
        val base = if (Themes.contrast(white, avg) >= Themes.contrast(black, avg)) white else black
        var out = base
        var w = 0f
        while (Themes.contrast(out, avg) < 4.5f && w < 1f) {
            w = (w + 0.15f).coerceAtMost(1f)
            out = androidx.compose.ui.graphics.lerp(base, if (base == white) black else white, w)
        }
        return out
    }

    /** True when the style's average is light. */
    fun isLight(s: BubbleStyle): Boolean =
        s.colors.isNotEmpty() && s.colors.map { it.luminance() }.average() > 0.5f
}
