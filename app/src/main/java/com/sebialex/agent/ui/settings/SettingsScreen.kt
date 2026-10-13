package com.sebialex.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.llm.ReasoningPreference
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

@Composable
fun SettingsScreen(
    settings: SecureSettings,
    models: OpenRouterModels,
    termux: com.sebialex.agent.data.shell.TermuxBridge,
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
            ReasoningSetting(
                model = snap.model,
                pref = snap.reasoning,
                models = models,
                onEnabled = { settings.setReasoningEnabled(snap.model, it) },
                onEffort = { settings.setReasoningEffort(snap.model, it) },
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
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { settings.setAutoApprove(!snap.autoApprove) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-approve agent actions", style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                    Text(
                        "Installs, deletions and network commands run without the Allow/Deny card. Each one is marked in the steps. Blocked actions stay blocked. Off by default.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextTertiary,
                        modifier = Modifier.padding(top = 2.dp, end = 12.dp),
                    )
                }
                Switch(
                    checked = snap.autoApprove,
                    onCheckedChange = settings::setAutoApprove,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.OnAccent,
                        checkedTrackColor = Palette.Accent,
                        uncheckedThumbColor = Palette.IconMuted,
                        uncheckedTrackColor = Palette.SurfaceHigh,
                        uncheckedBorderColor = Palette.SurfaceHighest,
                    ),
                )
            }
            SandboxSetting(termux)
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

private sealed interface ModelCheck {
    data object Loading : ModelCheck
    data class Ready(val info: ModelReasoning) : ModelCheck
    data object Failed : ModelCheck
}

/**
 * Reasoning toggle for the selected model, driven by its live OpenRouter metadata.
 * Unsupported models get a disabled switch; mandatory models have no off state; effort
 * choices come from the model's supported_efforts. The choice is stored per model.
 */
@Composable
private fun ReasoningSetting(
    model: String,
    pref: ReasoningPreference,
    models: OpenRouterModels,
    onEnabled: (Boolean) -> Unit,
    onEffort: (String) -> Unit,
) {
    var check by remember(model) { mutableStateOf<ModelCheck>(ModelCheck.Loading) }
    var attempt by remember(model) { mutableIntStateOf(0) }
    LaunchedEffect(model, attempt) {
        check = ModelCheck.Loading
        check = try {
            ModelCheck.Ready(models.reasoning(model, refresh = attempt > 0))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelCheck.Failed
        }
    }
    val info = (check as? ModelCheck.Ready)?.info
    val supported = info?.supported == true
    val resolved = info?.let { ReasoningRules.resolve(it, pref) }
    val locked = info != null && supported && !info.canDisable
    val status = when {
        check is ModelCheck.Loading -> "checking model"
        check is ModelCheck.Failed -> "could not check model, tap to retry"
        !supported -> "not supported"
        locked -> "always on for this model"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = check is ModelCheck.Failed || (supported && !locked)) {
                    if (check is ModelCheck.Failed) attempt++ else onEnabled(resolved?.on != true)
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Reasoning",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (supported) Palette.TextPrimary else Palette.TextTertiary,
                )
                if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            }
            // Mandatory reasoning has no off option, so no switch is shown at all.
            if (!locked) Switch(
                checked = resolved?.on == true,
                onCheckedChange = onEnabled,
                enabled = supported,
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
        val choices = info?.effortChoices.orEmpty()
        if (resolved?.on == true && choices.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                choices.forEach { effort ->
                    val selected = effort == resolved.effort
                    Text(
                        effort,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Palette.OnAccent else Palette.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Palette.Accent else Palette.SurfaceHigh)
                            .clickable { onEffort(effort) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
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
