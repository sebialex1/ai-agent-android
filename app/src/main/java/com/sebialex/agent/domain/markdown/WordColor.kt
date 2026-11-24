package com.sebialex.agent.domain.markdown

/**
 * Makes a model-picked colour readable where it lands. The chat's answers sit on the theme's
 * dark ink, and the model has no idea which theme that is: it picks a hex for the meaning
 * ("red for a warning") and can easily pick one that fades into the background — a dark gray
 * on the dark theme is indistinguishable from no colour at all, which reads as "colouring does
 * not work". These are the same numbers the theme engine uses for its own labels (WCAG relative
 * luminance, contrast ratio), applied to the picked hex in one pure, testable place.
 */
object WordColor {

    /** A picked colour must stand out from the surface it is read on by at least this. */
    private const val MIN_CONTRAST = 2.5f

    /** WCAG relative luminance of an `0xRRGGBB` int. */
    fun luminance(rgb: Int): Float {
        fun channel(bits: Int): Double {
            val c = (bits and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel(rgb shr 16)
        val g = channel(rgb shr 8)
        val b = channel(rgb)
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
    }

    /** WCAG contrast ratio between two `0xRRGGBB` ints; 1.0 is no difference, 21.0 black on white. */
    fun contrast(a: Int, b: Int): Float {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * [hex] (six hex digits, with or without the leading `#`) pushed in even steps toward
     * [fallback] (a `0xRRGGBB` int, the theme's normal text colour) until it stands out on
     * [bg]. The hue is kept as far as possible: only an unreadable pick is diluted, a bright
     * or already-contrasting colour comes back unchanged. Returns six uppercase hex digits
     * without the `#`.
     */
    fun readableOn(hex: String, bg: Int, fallback: Int): String {
        val wanted = hex.removePrefix("#").toLong(16).toInt()
        var out = wanted
        var w = 0f
        while (contrast(out, bg) < MIN_CONTRAST && w < 1f) {
            w = (w + 0.2f).coerceAtMost(1f)
            out = mix(wanted, fallback, w)
        }
        return String.format("%06X", out and 0xFFFFFF)
    }

    /** Per-channel linear mix: `w = 0` is [a], `w = 1` is [b]. */
    private fun mix(a: Int, b: Int, w: Float): Int {
        fun mixChannel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * w).toInt().coerceIn(0, 255)
        }
        return (mixChannel(16) shl 16) or (mixChannel(8) shl 8) or mixChannel(0)
    }
}
