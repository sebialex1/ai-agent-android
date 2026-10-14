package com.sebialex.agent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette

/** Small uppercase-free section label, same style as field labels. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = modifier)
}

/**
 * A row with a title and a switch. The explanation, when there is one, is not printed under the
 * title: it sits behind a small info button next to it (one tap shows it).
 */
@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    var help by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { onChange(!checked) }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) Palette.TextPrimary else Palette.TextTertiary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (subtitle != null) InfoButton(help, onToggle = { help = !help }, what = title)
            }
            Spacer(Modifier.width(8.dp))
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
        if (subtitle != null) HelpText(subtitle, help)
    }
}

/** The small info button that shows or hides a row's explanation. */
@Composable
internal fun InfoButton(expanded: Boolean, onToggle: () -> Unit, what: String = "") {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(R.drawable.ic_info, if (expanded) "Hide details" else "Details $what", tint = if (expanded) Palette.IconActive else Palette.TextTertiary, size = 15.dp)
    }
}

/** An explanation that is hidden until its [InfoButton] is tapped. */
@Composable
internal fun HelpText(text: String, visible: Boolean) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(bottom = 6.dp, end = 8.dp))
    }
}

/** A long note collapsed to a single "Details" line; tapping it shows the text. */
@Composable
internal fun Help(text: String, label: String = "Details") {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_info, null, tint = Palette.TextTertiary, size = 13.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        HelpText(text, open)
    }
}

/**
 * The one row of every settings page: optional icon, title, a short value under it, and a
 * chevron when it opens something. [onClick] null: a plain row.
 */
@Composable
internal fun NavRow(
    title: String,
    value: String? = null,
    @androidx.annotation.DrawableRes icon: Int? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    expanded: Boolean? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, null, tint = Palette.IconMuted, size = 18.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!value.isNullOrEmpty()) Text(value, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            AppIcon(
                when (expanded) { true -> R.drawable.ic_chevron_down; else -> R.drawable.ic_chevron_right },
                null, tint = Palette.IconMuted, size = 16.dp,
            )
        }
    }
}

/** A [NavRow] that opens its [content] in place (collapsed by default). */
@Composable
internal fun ExpandRow(
    title: String,
    value: String? = null,
    @androidx.annotation.DrawableRes icon: Int? = null,
    key: String = title,
    initiallyOpen: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(key) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        NavRow(title, value, icon, onClick = { open = !open }, expanded = open, trailing = trailing)
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = if (icon != null) 32.dp else 0.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

/** Hairline between rows of one card. */
@Composable
internal fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.SurfaceHigh))
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

/**
 * A titled group of settings: a quiet label (with an info button when [help] is given) over one
 * card that holds the group's rows. Every settings page is built from these, so all groups look
 * the same: one card per group, never a card per row.
 */
@Composable
internal fun SettingsGroup(title: String, modifier: Modifier = Modifier, help: String? = null, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(start = 4.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(title)
            if (help != null) InfoButton(open, onToggle = { open = !open }, what = title)
        }
        if (help != null) Box(Modifier.padding(start = 4.dp)) { HelpText(help, open) }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(SettingsCardShape)
                .background(Palette.Surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content()
        }
    }
}

internal val SettingsCardShape = RoundedCornerShape(18.dp)
