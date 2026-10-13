package com.sebialex.agent.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tactile surface: quiet shadow, warm charcoal fill and a barely visible top-edge highlight.
 * No borders in the conventional sense; the stroke fades out after the top edge.
 */
fun Modifier.softSurface(
    shape: Shape,
    color: Color = Palette.SurfaceHigh,
    elevation: Dp = 6.dp,
    highlight: Boolean = true,
): Modifier {
    var m = this
    if (elevation > 0.dp) {
        m = m.shadow(elevation = elevation, shape = shape, ambientColor = Color.Black, spotColor = Color.Black, clip = false)
    }
    m = m.clip(shape).background(color, shape)
    if (highlight) {
        m = m.border(
            width = 0.75.dp,
            brush = Brush.verticalGradient(
                0f to Palette.Highlight,
                0.35f to Color.Transparent,
                1f to Color.Transparent,
            ),
            shape = shape,
        )
    }
    return m
}
