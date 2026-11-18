package com.sebialex.agent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * One selectable theme: near-black Discord-style surfaces, a background gradient and one accent.
 * Text colors are never stored with the theme: they are derived from the surfaces (see
 * [textColorsFor]) with a minimum contrast ratio, so any theme keeps every label readable.
 */
data class ThemePreset(
    val id: String,
    val label: String,
    /** App background (top of the gradient is drawn over it). */
    val ink: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val accent: Color,
    /** Background gradient, top to bottom; [ink] must be part of it so edges blend. */
    val gradient: List<Color>,
    /**
     * Optional backdrop decoration drawn over the gradient ("stars", "braille", "liquid"),
     * rendered by [Backdrops]; empty for plain gradient themes.
     */
    val decor: String = "",
)

/** Text colors derived for one theme; [Palette.apply] writes them into the live palette. */
data class ThemeTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val onAccent: Color,
    val link: Color,
)

object Themes {

    /** The original warm charcoal palette. */
    val MIDNIGHT = ThemePreset(
        id = "midnight", label = "Midnight",
        ink = Color(0xFF0B0A09), surface = Color(0xFF151412), surfaceHigh = Color(0xFF1D1B19),
        surfaceHighest = Color(0xFF262421), accent = Color(0xFFF2EEE8),
        gradient = listOf(Color(0xFF0B0A09), Color(0xFF100E0C), Color(0xFF0B0A09)),
    )

    /** Discord dark: graphite surfaces with the blurple accent and a faint indigo gradient. */
    val BLURPLE = ThemePreset(
        id = "blurple", label = "Blurple",
        ink = Color(0xFF1E1F22), surface = Color(0xFF2B2D31), surfaceHigh = Color(0xFF313338),
        surfaceHighest = Color(0xFF3F4147), accent = Color(0xFF5865F2),
        gradient = listOf(Color(0xFF1E1F22), Color(0xFF232433), Color(0xFF1E1F22)),
    )

    /** Discord's old home-feel gradient: deep violet into blurple-tinted dark. */
    val VIOLET = ThemePreset(
        id = "violet", label = "Violet dusk",
        ink = Color(0xFF15101F), surface = Color(0xFF1E1830), surfaceHigh = Color(0xFF261F3D),
        surfaceHighest = Color(0xFF332A4F), accent = Color(0xFF9B6DFF),
        gradient = listOf(Color(0xFF15101F), Color(0xFF241539), Color(0xFF1A1030), Color(0xFF15101F)),
    )

    /** Cool blue-slate gradient, Discord night mode with a teal accent. */
    val OCEAN = ThemePreset(
        id = "ocean", label = "Deep ocean",
        ink = Color(0xFF0D1420), surface = Color(0xFF16202F), surfaceHigh = Color(0xFF1C2939),
        surfaceHighest = Color(0xFF26364B), accent = Color(0xFF3BA2E0),
        gradient = listOf(Color(0xFF0D1420), Color(0xFF10203A), Color(0xFF0D1420)),
    )

    /** Light cream with a caramel accent; the warm light counterpart of Midnight. */
    val WHITE_CARAMEL = ThemePreset(
        id = "white-caramel", label = "White caramel",
        ink = Color(0xFFF7F1E6), surface = Color(0xFFFFF9EF), surfaceHigh = Color(0xFFF4E8D4),
        surfaceHighest = Color(0xFFEAD9BC), accent = Color(0xFFB87B2E),
        gradient = listOf(Color(0xFFF7F1E6), Color(0xFFFAEEDC), Color(0xFFF3E3CC), Color(0xFFF7F1E6)),
    )

    /** Near-white cool gray with a blue accent. */
    val SNOW = ThemePreset(
        id = "snow", label = "Snow",
        ink = Color(0xFFF2F4F7), surface = Color(0xFFFBFCFE), surfaceHigh = Color(0xFFE9EDF3),
        surfaceHighest = Color(0xFFDBE2EB), accent = Color(0xFF2E6FD8),
        gradient = listOf(Color(0xFFF2F4F7), Color(0xFFE8EEF6), Color(0xFFF2F4F7)),
    )

    /** Light green-tinted paper with a deep matcha accent. */
    val MATCHA = ThemePreset(
        id = "matcha", label = "Matcha",
        ink = Color(0xFFF1F5EC), surface = Color(0xFFFAFCF5), surfaceHigh = Color(0xFFE4EDDA),
        surfaceHighest = Color(0xFFD5E2C6), accent = Color(0xFF4C7A3F),
        gradient = listOf(Color(0xFFF1F5EC), Color(0xFFE6F0DC), Color(0xFFF1F5EC)),
    )

    /** Light rose-tinted paper with a berry accent. */
    val ROSEWATER = ThemePreset(
        id = "rosewater", label = "Rosewater",
        ink = Color(0xFFF8F1F3), surface = Color(0xFFFDF8F9), surfaceHigh = Color(0xFFF2E2E7),
        surfaceHighest = Color(0xFFE8D2DA), accent = Color(0xFFB04A6E),
        gradient = listOf(Color(0xFFF8F1F3), Color(0xFFF4E6EB), Color(0xFFF8F1F3)),
    )

    /** Nord's blue-gray with frost accent. */
    val NORDIC = ThemePreset(
        id = "nordic", label = "Nordic",
        ink = Color(0xFF2E3440), surface = Color(0xFF3B4252), surfaceHigh = Color(0xFF434C5E),
        surfaceHighest = Color(0xFF4C566A), accent = Color(0xFF88C0D0),
        gradient = listOf(Color(0xFF2E3440), Color(0xFF353C4C), Color(0xFF2E3440)),
    )

    /** Charcoal with a warm ember accent. */
    val EMBER = ThemePreset(
        id = "ember", label = "Ember",
        ink = Color(0xFF141010), surface = Color(0xFF201818), surfaceHigh = Color(0xFF2A1F1E),
        surfaceHighest = Color(0xFF372827), accent = Color(0xFFFF7A59),
        gradient = listOf(Color(0xFF141010), Color(0xFF1F1412), Color(0xFF141010)),
    )

    /** Deep forest green with a light leaf accent. */
    val FOREST = ThemePreset(
        id = "forest", label = "Forest",
        ink = Color(0xFF0D1A12), surface = Color(0xFF16271C), surfaceHigh = Color(0xFF1D3124),
        surfaceHighest = Color(0xFF27402F), accent = Color(0xFF7FD6A4),
        gradient = listOf(Color(0xFF0D1A12), Color(0xFF12241A), Color(0xFF0D1A12)),
    )

    /** Rich grape purple with a bright lilac accent. */
    val GRAPE = ThemePreset(
        id = "grape", label = "Grape",
        ink = Color(0xFF170F22), surface = Color(0xFF241833), surfaceHigh = Color(0xFF2D2040),
        surfaceHighest = Color(0xFF3A2A52), accent = Color(0xFFC08BFF),
        gradient = listOf(Color(0xFF170F22), Color(0xFF221539), Color(0xFF170F22)),
    )

    /** Night sky: near-black navy with a scatter of fixed stars over the gradient. */
    val STARS = ThemePreset(
        id = "stars", label = "Stars",
        ink = Color(0xFF060913), surface = Color(0xFF101527), surfaceHigh = Color(0xFF161C33),
        surfaceHighest = Color(0xFF1F2742), accent = Color(0xFF9DB8FF),
        gradient = listOf(Color(0xFF060913), Color(0xFF0C1226), Color(0xFF111A33), Color(0xFF060913)),
        decor = "stars",
    )

    /** Raised-dot paper: a faint braille cell grid over warm charcoal. */
    val BRAILLE = ThemePreset(
        id = "braille", label = "Braille",
        ink = Color(0xFF121110), surface = Color(0xFF1C1A18), surfaceHigh = Color(0xFF242220),
        surfaceHighest = Color(0xFF2F2C29), accent = Color(0xFFD8B26A),
        gradient = listOf(Color(0xFF121110), Color(0xFF191715), Color(0xFF121110)),
        decor = "braille",
    )

    /** Frosted translucent surfaces over drifting color blobs; the glass carries the theme. */
    val LIQUID_GLASS = ThemePreset(
        id = "liquid-glass", label = "Liquid glass",
        ink = Color(0xFF0A1220), surface = Color(0xCC16233B), surfaceHigh = Color(0xB31E304E),
        surfaceHighest = Color(0x992A4066), accent = Color(0xFF7DD3FC),
        gradient = listOf(Color(0xFF0A1220), Color(0xFF0E1B30), Color(0xFF0A1220)),
        decor = "liquid",
    )

    /** Aurora: deep polar teal with drifting liquid light over it. */
    val AURORA = ThemePreset(
        id = "aurora", label = "Aurora",
        ink = Color(0xFF04141A), surface = Color(0xCC0C2430), surfaceHigh = Color(0xB3123240),
        surfaceHighest = Color(0x991B4557), accent = Color(0xFF5EEAD4),
        gradient = listOf(Color(0xFF04141A), Color(0xFF072A2E), Color(0xFF0A1F33), Color(0xFF04141A)),
        decor = "liquid",
    )

    /** Nebula: violet-magenta deep field with a fixed star scatter. */
    val NEBULA = ThemePreset(
        id = "nebula", label = "Nebula",
        ink = Color(0xFF0B0616), surface = Color(0xFF170E28), surfaceHigh = Color(0xFF201436),
        surfaceHighest = Color(0xFF2C1C49), accent = Color(0xFFF0ABFC),
        gradient = listOf(Color(0xFF0B0616), Color(0xFF1B0B33), Color(0xFF2A0F3D), Color(0xFF0B0616)),
        decor = "stars",
    )

    /** Obsidian: near-black glass with a faint raised-dot grid. */
    val OBSIDIAN = ThemePreset(
        id = "obsidian", label = "Obsidian",
        ink = Color(0xFF050506), surface = Color(0xFF101013), surfaceHigh = Color(0xFF17171C),
        surfaceHighest = Color(0xFF212129), accent = Color(0xFFE4E4E7),
        gradient = listOf(Color(0xFF050506), Color(0xFF0B0B0F), Color(0xFF050506)),
        decor = "braille",
    )

    /** Sakura: light blossom paper with soft liquid colour behind frosted surfaces. */
    val SAKURA = ThemePreset(
        id = "sakura", label = "Sakura",
        ink = Color(0xFFFDF2F6), surface = Color(0xCCFFFFFF), surfaceHigh = Color(0xB3FBE4EE),
        surfaceHighest = Color(0x99F6CFDF), accent = Color(0xFFDB2777),
        gradient = listOf(Color(0xFFFDF2F6), Color(0xFFFCE7F1), Color(0xFFFDF2F6)),
        decor = "liquid",
    )

    /** Deep space: the darkest blue field, stars over it, ice-blue accent. */
    val DEEP_SPACE = ThemePreset(
        id = "deep-space", label = "Deep space",
        ink = Color(0xFF01030A), surface = Color(0xFF080D1C), surfaceHigh = Color(0xFF0E1528),
        surfaceHighest = Color(0xFF161F38), accent = Color(0xFF7DD3FC),
        gradient = listOf(Color(0xFF01030A), Color(0xFF050B1A), Color(0xFF0A1226), Color(0xFF01030A)),
        decor = "stars",
    )

    /** Copper: warm dark metal with a raised-dot grid and a burnished accent. */
    val COPPER = ThemePreset(
        id = "copper", label = "Copper",
        ink = Color(0xFF140D08), surface = Color(0xFF20150E), surfaceHigh = Color(0xFF2A1C13),
        surfaceHighest = Color(0xFF38261A), accent = Color(0xFFE0955F),
        gradient = listOf(Color(0xFF140D08), Color(0xFF1D1209), Color(0xFF140D08)),
        decor = "braille",
    )

    val ALL = listOf(
        MIDNIGHT, BLURPLE, VIOLET, OCEAN, NORDIC, EMBER, FOREST, GRAPE,
        STARS, BRAILLE, LIQUID_GLASS,
        AURORA, NEBULA, OBSIDIAN, DEEP_SPACE, COPPER,
        WHITE_CARAMEL, SNOW, MATCHA, ROSEWATER, SAKURA,
    )
    val DEFAULT = MIDNIGHT

    fun byId(id: String): ThemePreset = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /**
     * Text for [t]: the base color (near-white on dark surfaces, near-black on light ones) is the
     * readable end of the scale; secondary and tertiary are mixed toward the surface and then
     * pushed apart until they clear their minimum contrast ratio. Every text in the app reads
     * these, so a theme can never break its own labels.
     */
    fun textColorsFor(t: ThemePreset): ThemeTextColors {
        val bg = t.surface
        val white = Color(0xFFF4F2F0)
        val black = Color(0xFF141210)
        val base = if (contrast(white, bg) >= contrast(black, bg)) white else black
        return ThemeTextColors(
            primary = withContrast(base, t.ink, 7f, base),
            secondary = withContrast(lerp(base, bg, 0.45f), bg, 4.5f, base),
            tertiary = withContrast(lerp(base, bg, 0.62f), bg, 2.6f, base),
            onAccent = if (contrast(white, t.accent) >= contrast(black, t.accent)) white else black,
            link = withContrast(lerp(base, t.accent, 0.45f), t.ink, 4.5f, base),
        )
    }

    /** WCAG contrast ratio between two colors (1..21). */
    fun contrast(a: Color, b: Color): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** Pushes [c] toward [away] until it clears [min] contrast on [bg] (a few steps is always enough). */
    private fun withContrast(c: Color, bg: Color, min: Float, away: Color): Color {
        var out = c
        var w = 0f
        while (contrast(out, bg) < min && w < 1f) {
            w = (w + 0.12f).coerceAtMost(1f)
            out = lerp(c, away, w)
        }
        return out
    }
}
