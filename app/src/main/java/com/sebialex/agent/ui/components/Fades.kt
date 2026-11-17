package com.sebialex.agent.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
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

/**
 * A message rising into place: fades in and slides up a little, once. [enabled] is false for
 * messages that already existed, so scrolling back through a chat never replays the animation.
 */
@androidx.compose.runtime.Composable
fun Modifier.bubbleEnter(enabled: Boolean): Modifier {
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (enabled) 0f else 1f) }
    androidx.compose.runtime.LaunchedEffect(enabled) {
        if (enabled) {
            progress.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 26.dp.toPx()
    }
}

/**
 * The "busy" gradient: the content's own glyphs are recolored (SrcIn on an offscreen layer) with
 * a gray band that drifts left to right, so a running step or a generating conversation reads as
 * in motion without a spinner. Shared by the conversation list and the step rows so both move
 * identically. The animation is read in the draw phase only and never recomposes the row.
 */
@androidx.compose.runtime.Composable
fun Modifier.busyShimmer(): Modifier {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "busy")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "busyBand",
    )
    return this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val band = (size.width * 0.55f).coerceAtLeast(80.dp.toPx())
            val x = -band + (size.width + band * 2f) * progress
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color(0xFF8C877F),
                    0.5f to Color(0xFFE6E1DA),
                    1f to Color(0xFF8C877F),
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x + band, 0f),
                    tileMode = androidx.compose.ui.graphics.TileMode.Clamp,
                ),
                blendMode = BlendMode.SrcIn,
            )
        }
}
