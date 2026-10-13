package com.sebialex.agent.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sebialex.agent.ui.theme.Palette

/**
 * Settings tab bar: plain labels spread across the full width with a short accent underline
 * under the selected one, over a hairline divider. Every label stays visible on a phone, so
 * nothing scrolls sideways; the selected tab reads bright and semibold, the rest stay quiet.
 */
@Composable
internal fun <T> SettingsTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { o ->
                val on = o == selected
                val text by animateColorAsState(if (on) Palette.TextPrimary else Palette.TextTertiary, tween(160), label = "tabText")
                val bar by animateColorAsState(if (on) Palette.Accent else Color.Transparent, tween(160), label = "tabBar")
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .selectable(selected = on, role = Role.Tab) { onSelect(o) }
                        .padding(horizontal = 8.dp)
                        .padding(top = 10.dp)
                        .width(IntrinsicSize.Max),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label(o),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = text,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(50))
                            .background(bar),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.SurfaceHigh))
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
