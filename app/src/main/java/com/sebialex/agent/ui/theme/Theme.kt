package com.sebialex.agent.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val AgentColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.SurfaceHighest,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.TextSecondary,
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.SurfaceHigh,
    onSecondaryContainer = Palette.TextPrimary,
    tertiary = Palette.Warning,
    background = Palette.Ink,
    onBackground = Palette.TextPrimary,
    surface = Palette.Ink,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHighest,
    outline = Palette.SurfaceHighest,
    outlineVariant = Palette.SurfaceHigh,
    error = Palette.Danger,
    onError = Palette.Ink,
    scrim = Palette.Ink,
)

private val AgentShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AgentColors, typography = AgentTypography, shapes = AgentShapes) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = Palette.TextPrimary,
                backgroundColor = Palette.TextPrimary.copy(alpha = 0.25f),
            ),
            content = content,
        )
    }
}
