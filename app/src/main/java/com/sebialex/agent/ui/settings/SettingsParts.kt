package com.sebialex.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sebialex.agent.ui.theme.Palette

/** Small uppercase-free section label, same style as field labels. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = modifier)
}

/** A row with a title, optional explanation and a switch, matching the existing settings rows. */
@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Palette.TextPrimary else Palette.TextTertiary)
            if (subtitle != null) Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.IconMuted,
                uncheckedTrackColor = Palette.SurfaceHigh,
                uncheckedBorderColor = Palette.SurfaceHighest,
                disabledUncheckedThumbColor = Palette.SurfaceHighest,
                disabledUncheckedTrackColor = Palette.Surface,
                disabledUncheckedBorderColor = Palette.SurfaceHigh,
            ),
        )
    }
}

/**
 * Choice control: one quiet track with the selected option highlighted inside it (a segmented
 * control), instead of a separate pill per option.
 */
@Composable
internal fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Text(
                label(o),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) Palette.TextPrimary else Palette.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Palette.SurfaceHighest else Color.Transparent)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Action button used in rows ("Download", "Use", "Delete", "Allow"). Only the one primary action
 * of a row is filled; the others are plain text so a row never shows a cluster of pills.
 */
@Composable
internal fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> Palette.TextTertiary
            primary -> Palette.OnAccent
            danger -> Palette.Danger
            else -> Palette.TextPrimary
        },
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .then(if (primary && enabled) Modifier.background(Palette.Accent) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A titled group of settings: a quiet label, then its rows with even spacing. */
@Composable
internal fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title)
        content()
    }
}
