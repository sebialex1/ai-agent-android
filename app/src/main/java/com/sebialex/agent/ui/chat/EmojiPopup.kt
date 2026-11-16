package com.sebialex.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * The emoji suggestions that open above the input while a `:code` is being typed: one tall
 * rounded row, Discord style — the emoji and its name side by side, no separation, scrolling
 * horizontally with faded edges when the matches overflow.
 */
@Composable
fun EmojiPopup(
    entries: List<EmojiEntry>,
    onPick: (EmojiEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val shape = RoundedCornerShape(20.dp)
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .softSurface(shape, Palette.Surface, elevation = 12.dp)
            .clip(shape)
            .heightIn(min = 54.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fadeEdges()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            entries.forEach { e ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(e)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(e.emoji, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        e.code,
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Fades both horizontal edges of the content with destination-in gradient masks. */
private fun Modifier.fadeEdges(width: Dp = 24.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val w = width.toPx().coerceAtMost(size.width / 2f)
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = w),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = size.width - w, endX = size.width),
            blendMode = BlendMode.DstIn,
        )
    }
