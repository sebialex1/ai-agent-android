package com.sebialex.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.ThemePreset
import com.sebialex.agent.ui.theme.Themes

/** Settings > Appearance: the app theme. Text colors adapt to every theme automatically. */
@Composable
internal fun AppearanceSettings(settings: SecureSettings, snap: SecureSettings.Snapshot) {
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
