package com.sebialex.agent.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * The app's one bottom sheet look: warm charcoal surface, soft scrim and a small handle. Every
 * option menu (chat options, file actions, pickers) uses this instead of a plain Material menu,
 * so nothing falls back to the system's default light popup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        scrimColor = Palette.Ink.copy(alpha = 0.6f),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.SurfaceHighest))
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (title != null) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            content()
        }
    }
}

/** One tappable row in an [AppSheet]: icon, title, optional detail. */
@Composable
fun SheetOption(
    @DrawableRes icon: Int,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color: Color = when {
        !enabled -> Palette.TextTertiary
        danger -> Palette.Danger
        else -> Palette.TextPrimary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, null, tint = if (danger) Palette.Danger else Palette.IconMuted, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

/** A switch row in an [AppSheet]. The whole row toggles. */
@Composable
fun SheetSwitch(
    @DrawableRes icon: Int,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SheetOption(icon, title, onClick = { onChange(!checked) }, subtitle = subtitle, enabled = enabled) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = appSwitchColors(),
        )
    }
}

@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.OnAccent,
    checkedTrackColor = Palette.Accent,
    uncheckedThumbColor = Palette.IconMuted,
    uncheckedTrackColor = Palette.SurfaceHigh,
    uncheckedBorderColor = Palette.SurfaceHighest,
    disabledUncheckedThumbColor = Palette.SurfaceHighest,
    disabledUncheckedTrackColor = Palette.Surface,
    disabledUncheckedBorderColor = Palette.SurfaceHigh,
)

/** Thin separator between groups of options. */
@Composable
fun SheetDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).heightIn(min = 1.dp, max = 1.dp).background(Palette.SurfaceHigh))
}

/** Single-line themed input with a confirm button, used for rename and similar prompts. */
@Composable
fun SheetTextField(
    initial: String,
    placeholder: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .softSurface(RoundedCornerShape(16.dp), Palette.SurfaceHigh, elevation = 2.dp)
            .heightIn(min = 50.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) onSubmit(draft.trim()) }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Palette.TextTertiary, maxLines = 1)
                    inner()
                },
            )
        }
        QuietIconButton(R.drawable.ic_check, "Save", onClick = { if (draft.isNotBlank()) onSubmit(draft.trim()) }, enabled = draft.isNotBlank())
    }
}

/** Two-button confirmation inside a sheet ("Cancel" / destructive action). */
@Composable
fun SheetConfirm(message: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton("Cancel", onCancel, Modifier.weight(1f))
            SheetButton(confirm, onConfirm, Modifier.weight(1f), danger = true)
        }
    }
}

@Composable
fun SheetButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, primary: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    primary -> Palette.Accent
                    danger -> Palette.Danger.copy(alpha = 0.16f)
                    else -> Palette.SurfaceHigh
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                primary -> Palette.OnAccent
                danger -> Palette.Danger
                else -> Palette.TextPrimary
            },
        )
    }
}
