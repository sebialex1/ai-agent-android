package com.sebialex.agent.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The live palette. Its values are written by [apply] whenever the selected theme changes (read
 * inside composition, so the whole app recomposes with the new colors) and are otherwise used
 * exactly like fixed colors. Text colors come from the theme's surfaces with a minimum contrast
 * (see [Themes.textColorsFor]), so no theme can leave text unreadable.
 */
object Palette {
    var Ink = Color(0xFF0B0A09)            // app background
    var Surface = Color(0xFF151412)        // raised rows / sheets
    var SurfaceHigh = Color(0xFF1D1B19)    // pills, input
    var SurfaceHighest = Color(0xFF262421) // pressed / chips
    var Highlight = Color(0x14FFFFFF)      // soft top-edge highlight
    var TextPrimary = Color(0xFFF2EEE8)
    var TextSecondary = Color(0xFFA9A49C)
    var TextTertiary = Color(0xFF716C65)
    var IconActive = Color(0xFFF2EEE8)
    var IconMuted = Color(0xFF8E8981)
    var Accent = Color(0xFFF2EEE8)         // send button fill
    var OnAccent = Color(0xFF0B0A09)
    var Warning = Color(0xFFE2B26A)
    var Danger = Color(0xFFE07A6A)
    var DiffAdd = Color(0x1F7FCF8F)
    var DiffAddText = Color(0xFFA9DDB2)
    var DiffRemove = Color(0x1FE07A6A)
    var DiffRemoveText = Color(0xFFEBA79C)
    var CodeBackground = Color(0xFF121110)
    var Link = Color(0xFFD9CFC2)

    /** The background gradient of the current theme, top to bottom. */
    var Gradient: List<Color> = listOf(Ink)

    /** Applies [t] to every live color, deriving the text colors from its surfaces. */
    fun apply(t: ThemePreset) {
        Ink = t.ink
        Surface = t.surface
        SurfaceHigh = t.surfaceHigh
        SurfaceHighest = t.surfaceHighest
        Accent = t.accent
        Gradient = t.gradient
        val text = Themes.textColorsFor(t)
        TextPrimary = text.primary
        TextSecondary = text.secondary
        TextTertiary = text.tertiary
        IconActive = text.primary
        IconMuted = text.tertiary
        OnAccent = text.onAccent
        Link = text.link
        // A code block sits a touch below the raised surfaces, whatever the theme.
        CodeBackground = lerp(t.ink, t.surface, 0.5f)
        Highlight = if (Themes.contrast(Color.White, t.surface) > Themes.contrast(Color.Black, t.surface)) {
            Color(0x14FFFFFF)
        } else {
            Color(0x14000000)
        }
        DiffAdd = Color(0x1F7FCF8F)
        DiffAddText = text.primary
        DiffRemove = Color(0x1FE07A6A)
        DiffRemoveText = text.primary
        // Semantic colors stay readable on any of the themes: pushed toward the text color when needed.
        Warning = pushReadable(Color(0xFFE2B26A), t.surface, text.primary)
        Danger = pushReadable(Color(0xFFE07A6A), t.surface, text.primary)
    }

    /** The app background: the theme's gradient instead of a flat fill. */
    fun backgroundBrush(): Brush = Brush.verticalGradient(Gradient)

    private fun lerp(a: Color, b: Color, w: Float) = androidx.compose.ui.graphics.lerp(a, b, w)

    /** Warns and errors must stay noticeable: at least 2.5 contrast on the surface. */
    private fun pushReadable(c: Color, bg: Color, away: Color): Color {
        var out = c
        var w = 0f
        while (Themes.contrast(out, bg) < 2.5f && w < 1f) {
            w = (w + 0.15f).coerceAtMost(1f)
            out = lerp(c, away, w)
        }
        return out
    }
}
