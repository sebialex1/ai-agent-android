package com.sebialex.agent.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dissolves the top [height] of the content with an alpha mask (destination-in), so scrolling
 * content fades out smoothly under a top bar instead of being cut off by a hard edge. The mask
 * is applied to the content only, so it works over any background. Apply it to the scrolling
 * container, below the bar (the bar itself sits above the container, not over it).
 */
fun Modifier.fadeTopEdge(height: Dp = 28.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val h = height.toPx().coerceAtMost(size.height)
        if (h > 0f) drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = h),
            size = Size(size.width, h),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Top fade for a scrolling list: only active once the list has scrolled, so the first item is
 * never dimmed at rest. [reverse] is for `reverseLayout` lists (the chat), where the content
 * hidden above the visible top is what [LazyListState.canScrollForward] reports.
 */
fun Modifier.fadeTopEdge(state: androidx.compose.foundation.lazy.LazyListState, reverse: Boolean = false, height: Dp = 28.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val hidden = if (reverse) state.canScrollForward else state.canScrollBackward
        val h = height.toPx().coerceAtMost(size.height)
        if (hidden && h > 0f) drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = h),
            size = Size(size.width, h),
            blendMode = BlendMode.DstIn,
        )
    }

/** Same for a [androidx.compose.foundation.verticalScroll] container. */
fun Modifier.fadeTopEdge(state: androidx.compose.foundation.ScrollState, height: Dp = 28.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val h = height.toPx().coerceAtMost(size.height)
        if (state.value > 0 && h > 0f) drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = h),
            size = Size(size.width, h),
            blendMode = BlendMode.DstIn,
        )
    }
