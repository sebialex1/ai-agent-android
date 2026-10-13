package com.sebialex.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

@Composable
fun SettingsScreen(
    settings: SecureSettings,
    onBack: () -> Unit,
    onClearConversations: () -> Unit,
) {
    val snap by settings.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIconButton(R.drawable.ic_back, "Back", onClick = onBack)
            Text("Settings", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, modifier = Modifier.padding(start = 8.dp))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            KeyField(
                label = "TinyFish API key",
                saved = snap.hasTinyFishKey,
                hint = snap.tinyFishHint,
                onSave = settings::setTinyFishKey,
                onClear = { settings.setTinyFishKey(null) },
            )
            KeyField(
                label = "OpenRouter API key",
                saved = snap.hasOpenRouterKey,
                hint = snap.openRouterHint,
                onSave = settings::setOpenRouterKey,
                onClear = { settings.setOpenRouterKey(null) },
            )
            PlainField(
                label = "Model",
                value = snap.model,
                placeholder = SecureSettings.DEFAULT_MODEL,
                onSave = settings::setModel,
            )
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { settings.setAskBeforeWeb(!snap.askBeforeWeb) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ask before web access", style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                Switch(
                    checked = snap.askBeforeWeb,
                    onCheckedChange = settings::setAskBeforeWeb,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.OnAccent,
                        checkedTrackColor = Palette.Accent,
                        uncheckedThumbColor = Palette.IconMuted,
                        uncheckedTrackColor = Palette.SurfaceHigh,
                        uncheckedBorderColor = Palette.SurfaceHighest,
                    ),
                )
            }
            Text(
                "Delete all conversations and workspaces",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Danger,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClearConversations).padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun KeyField(label: String, saved: Boolean, hint: String, onSave: (String) -> Unit, onClear: () -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
                .heightIn(min = 52.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_key, null, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text(if (saved) hint else "Paste key", style = MonoSmall, color = Palette.TextTertiary)
                        inner()
                    },
                )
            }
            when {
                value.isNotEmpty() -> QuietIconButton(R.drawable.ic_check, "Save $label", onClick = { onSave(value); value = "" })
                saved -> QuietIconButton(R.drawable.ic_close, "Remove $label", onClick = onClear, tint = Palette.IconMuted)
            }
        }
    }
}

@Composable
private fun PlainField(label: String, value: String, placeholder: String, onSave: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.trim() },
                    singleLine = true,
                    textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text(placeholder, style = MonoSmall, color = Palette.TextTertiary)
                        inner()
                    },
                )
            }
            if (draft != value) QuietIconButton(R.drawable.ic_check, "Save $label", onClick = { onSave(draft) })
        }
    }
}
