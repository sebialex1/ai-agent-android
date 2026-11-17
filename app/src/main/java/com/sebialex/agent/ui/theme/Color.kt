package com.sebialex.agent.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The live palette. Every value is snapshot state, so a composable that reads one is subscribed
 * to it and recomposes when [apply] writes a new theme — plain fields were read once and kept
 * the old theme's colors. Text colors come from the theme's surfaces with a minimum contrast
 * (see [Themes.textColorsFor]), so no theme can leave text unreadable.
 */
object Palette {
    var Ink by mutableStateOf(Color(0xFF0B0A09))            // app background
    var Surface by mutableStateOf(Color(0xFF151412))        // raised rows / sheets
    var SurfaceHigh by mutableStateOf(Color(0xFF1D1B19))    // pills, input
    var SurfaceHighest by mutableStateOf(Color(0xFF262421)) // pressed / chips
    var Highlight by mutableStateOf(Color(0x14FFFFFF))      // soft top-edge highlight
    var TextPrimary by mutableStateOf(Color(0xFFF2EEE8))
    var TextSecondary by mutableStateOf(Color(0xFFA9A49C))
    var TextTertiary by mutableStateOf(Color(0xFF716C65))
    var IconActive by mutableStateOf(Color(0xFFF2EEE8))
    var IconMuted by mutableStateOf(Color(0xFF8E8981))
    var Accent by mutableStateOf(Color(0xFFF2EEE8))         // send button fill
    var OnAccent by mutableStateOf(Color(0xFF0B0A09))
    var Warning by mutableStateOf(Color(0xFFE2B26A))
    var Danger by mutableStateOf(Color(0xFFE07A6A))
    var DiffAdd by mutableStateOf(Color(0x1F7FCF8F))
    var DiffAddText by mutableStateOf(Color(0xFFA9DDB2))
    var DiffRemove by mutableStateOf(Color(0x1FE07A6A))
    var DiffRemoveText by mutableStateOf(Color(0xFFEBA79C))
    var CodeBackground by mutableStateOf(Color(0xFF121110))
    var Link by mutableStateOf(Color(0xFFD9CFC2))

    /** The background gradient of the current theme, top to bottom. */
    var Gradient: List<Color> by mutableStateOf(listOf(Color(0xFF0B0A09)))

    /** The current theme's backdrop decoration ("", "stars", "braille", "liquid"). */
    var Decor: String by mutableStateOf("")

    /** Applies [t] to every live color, deriving the text colors from its surfaces. */
    fun apply(t: ThemePreset) {
        Ink = t.ink
        Surface = t.surface
        SurfaceHigh = t.surfaceHigh
        SurfaceHighest = t.surfaceHighest
        Accent = t.accent
        Gradient = t.gradient
        Decor = t.decor
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
