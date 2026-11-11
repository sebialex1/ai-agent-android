package com.sebialex.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.llm.ReasoningSupport
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette

/**
 * The thinking control for the selected model, built from what its provider supports: a plain
 * switch for on/off models, one segmented control for models with Auto and/or effort levels, a
 * short state line for always-on, unsupported and unknown models. It writes the same per-model
 * preference as before (reasoning_enabled:: / reasoning_effort::), so backups and older settings
 * keep working. Used on its own Settings sub-page and in the chat's + sheet.
 */
@Composable
internal fun ThinkingControl(settings: SecureSettings, snap: SecureSettings.Snapshot, models: OpenRouterModels) {
    var info by remember(snap.model) { mutableStateOf<ModelReasoning?>(null) }
    LaunchedEffect(snap.model, snap.customProviders, snap.reasoningOverrides) {
        info = try {
            models.reasoning(snap.model)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val current = info
    val choices = current?.let { ReasoningRules.choices(it) }.orEmpty()
    val selected = current?.let { ReasoningRules.selected(it, snap.reasoning) }
    val on = current != null && ReasoningRules.resolve(current, snap.reasoning).on
    val boolean = choices.map { it.id } == listOf(ReasoningRules.OFF, ReasoningRules.ON)
    // The model row right above already names the model, so only an unusual state is shown.
    val subtitle = when {
        current == null -> null
        current.support == ReasoningSupport.UNKNOWN ->
            if (CustomProviders.isCustom(snap.model)) "Support unknown \u00b7 set it in Settings > Models" else "Support unknown for this model"
        current.support == ReasoningSupport.UNSUPPORTED -> "Not available for this model"
        choices.size <= 1 -> "Always on for this model"
        !current.surfacesText -> "This provider does not return the reasoning text"
        else -> null
    }
    fun pick(id: String) = settings.setReasoning(snap.model, ReasoningRules.preferenceFor(id))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = boolean) { pick(if (on) ReasoningRules.OFF else ReasoningRules.ON) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_lightbulb, null, tint = Palette.IconMuted, size = 20.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Thinking", style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1)
            }
            // A switch only where on/off is all the model offers (or as a disabled indicator).
            if (boolean || choices.size <= 1) Switch(
                checked = on,
                enabled = boolean,
                onCheckedChange = { pick(if (it) ReasoningRules.ON else ReasoningRules.OFF) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.OnAccent,
                    checkedTrackColor = Palette.Accent,
                    uncheckedThumbColor = Palette.IconMuted,
                    uncheckedTrackColor = Palette.SurfaceHigh,
                    uncheckedBorderColor = Palette.SurfaceHighest,
                ),
            )
        }
        // Auto / Off / levels: one segmented control with exactly the options this model has.
        if (!boolean && choices.size > 1) {
            Box(Modifier.padding(start = 44.dp, end = 10.dp, bottom = 6.dp)) {
                ChoiceChips(
                    options = choices.map { it.id },
                    selected = selected ?: "",
                    label = { id -> choices.firstOrNull { it.id == id }?.label ?: id },
                    onSelect = { pick(it) },
                )
            }
        }
    }
}

/** Settings > Models > Thinking: the same control on its own sub-page. */
@Composable
internal fun ThinkingGroup(settings: SecureSettings, snap: SecureSettings.Snapshot, models: OpenRouterModels) {
    SettingsGroup("Thinking") {
        ThinkingControl(settings, snap, models)
    }
}
