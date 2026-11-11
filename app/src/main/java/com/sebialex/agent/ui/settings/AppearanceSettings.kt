package com.sebialex.agent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sebialex.agent.R
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.BubbleStyle
import com.sebialex.agent.ui.theme.BubbleStyles
import com.sebialex.agent.ui.theme.CustomBackground
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.ThemePreset
import com.sebialex.agent.ui.theme.Themes
import com.sebialex.agent.ui.theme.softSurface

/** Settings > Customization: theme, chat background image, and user bubble look. */
@Composable
internal fun AppearanceSettings(settings: SecureSettings, snap: SecureSettings.Snapshot) {
    var tab by rememberSaveable { mutableStateOf("Themes") }
    ChoiceChips(listOf("Themes", "Background", "Chat bubble"), tab, label = { it }, onSelect = { tab = it })
    when (tab) {
        "Background" -> BackgroundGroup(settings, snap)
        "Chat bubble" -> BubbleGroup(settings, snap)
        else -> ThemeGroup(settings, snap)
    }
}

@Composable
private fun ThemeGroup(settings: SecureSettings, snap: SecureSettings.Snapshot) {
    SettingsGroup("Theme") {
        Themes.ALL.forEach { t ->
            ThemeRow(t, selected = t.id == snap.theme, onClick = { settings.setTheme(t.id) })
            if (t.id != Themes.ALL.last().id) RowDivider()
        }
    }
}

@Composable
private fun ThemeRow(theme: ThemePreset, selected: Boolean, onClick: () -> Unit) {
    val text = remember(theme.id) { Themes.textColorsFor(theme) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(72.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(theme.gradient)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", style = MaterialTheme.typography.titleMedium, color = text.primary)
        }
        Spacer(Modifier.width(14.dp))
        Text(theme.label, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
        if (selected) AppIcon(R.drawable.ic_check, "Selected", tint = Palette.IconActive, size = 18.dp)
    }
}

@Composable
private fun BackgroundGroup(settings: SecureSettings, snap: SecureSettings.Snapshot) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && CustomBackground.save(context, uri)) settings.setCustomBackground(true)
    }
    val file = remember(snap.customBackground) { CustomBackground.file(context) }
    SettingsGroup("Background image") {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(72.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.backgroundBrush()),
                contentAlignment = Alignment.Center,
            ) {
                if (snap.customBackground && file.isFile) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("Aa", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
                }
            }
            PillButton(if (snap.customBackground) "Replace" else "Pick image", onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, primary = true)
            if (snap.customBackground) {
                PillButton("Remove", onClick = {
                    CustomBackground.clear(context)
                    settings.setCustomBackground(false)
                }, danger = true)
            }
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dim", style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Spacer(Modifier.width(16.dp))
            Slider(
                value = snap.backgroundDim.coerceIn(0, 70) / 70f,
                onValueChange = { settings.setBackgroundDim((it * 70).toInt()) },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${snap.backgroundDim}%",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

@Composable
private fun BubbleGroup(settings: SecureSettings, snap: SecureSettings.Snapshot) {
    SettingsGroup("User bubble") {
        BubbleStyles.ALL.forEach { s ->
            BubbleRow(s, selected = snap.bubbleStyle == s.id, onClick = { settings.setBubbleStyle(s.id) })
            if (s.id != BubbleStyles.ALL.last().id) RowDivider()
        }
    }
}

@Composable
private fun BubbleRow(style: BubbleStyle, selected: Boolean, onClick: () -> Unit) {
    val brush = remember(style.id) { BubbleStyles.brush(style) }
    val text = remember(style.id) { BubbleStyles.textColor(style, Palette.TextPrimary) }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(96.dp)
                .height(40.dp)
                .then(
                    if (brush == null) {
                        Modifier.softSurface(shape, Palette.SurfaceHigh, elevation = 2.dp)
                    } else {
                        Modifier
                            .shadow(2.dp, shape)
                            .clip(shape)
                            .background(brush)
                            .then(
                                when (style.effect) {
                                    "sheen" -> Modifier.drawWithContent {
                                        drawContent()
                                        drawRect(
                                            Brush.linearGradient(
                                                0f to Color.White.copy(alpha = 0.20f),
                                                0.45f to Color.Transparent,
                                                start = Offset.Zero,
                                                end = Offset(size.width, size.height),
                                            ),
                                        )
                                    }
                                    "glow" -> Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), shape)
                                    "glass" -> Modifier
                                        .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                Brush.verticalGradient(
                                                    0f to Color.White.copy(alpha = 0.12f),
                                                    1f to Color.Transparent,
                                                ),
                                            )
                                        }
                                    else -> Modifier
                                },
                            )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", style = MaterialTheme.typography.titleSmall, color = text)
        }
        Spacer(Modifier.width(14.dp))
        Text(style.label, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
        if (selected) AppIcon(R.drawable.ic_check, "Selected", tint = Palette.IconActive, size = 18.dp)
    }
}
