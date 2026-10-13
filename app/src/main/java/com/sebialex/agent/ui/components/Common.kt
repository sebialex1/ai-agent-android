package com.sebialex.agent.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sebialex.agent.ui.theme.Palette

@Composable
fun AppIcon(
    @DrawableRes res: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Palette.IconMuted,
    size: Dp = 22.dp,
) {
    Icon(painter = painterResource(res), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** Icon-only button. No background until pressed (bounded circular ripple). */
@Composable
fun QuietIconButton(
    @DrawableRes res: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.IconActive,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Palette.TextPrimary),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(res, contentDescription, tint = if (enabled) tint else Palette.TextTertiary, size = iconSize)
    }
}

/** Fades the right edge of the content with a destination-in gradient mask. */
fun Modifier.fadeRightEdge(width: Dp = 36.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val w = width.toPx().coerceAtMost(size.width)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = size.width - w,
                endX = size.width,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Always single-line; when the text overflows, the right edge fades out instead of using an ellipsis. */
@Composable
fun FadingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var overflow by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { overflow = it.hasVisualOverflow },
        modifier = if (overflow) modifier.fadeRightEdge() else modifier,
    )
}
