package com.sebialex.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * Text segmented control in the same pill style as the Workspace tabs: one raised pill, the
 * selected segment a shade lighter. Used for the Settings tabs and the provider switch.
 */
@Composable
internal fun <T> SegmentedText(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    /** Many options (the Settings tabs): the pill scrolls sideways instead of squeezing labels. */
    scrollable: Boolean = false,
) {
    Row(
        modifier
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .softSurface(RoundedCornerShape(50), Palette.SurfaceHigh, elevation = 2.dp)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                (if (fill) Modifier.weight(1f) else Modifier)
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Palette.SurfaceHighest else Palette.SurfaceHigh)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Palette.TextPrimary else Palette.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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

/** Horizontally scrolling choice chips, same look as the reasoning effort chips. */
@Composable
internal fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(
                label(o),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) Palette.OnAccent else Palette.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Palette.Accent else Palette.SurfaceHigh)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Small text button used inside cards ("Download", "Use", "Delete"). */
@Composable
internal fun PillButton(text: String, onClick: () -> Unit, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> Palette.TextTertiary
            primary -> Palette.OnAccent
            danger -> Palette.Danger
            else -> Palette.TextPrimary
        },
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary && enabled) Palette.Accent else Palette.SurfaceHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
