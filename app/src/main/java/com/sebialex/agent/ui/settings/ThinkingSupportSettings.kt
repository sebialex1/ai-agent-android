package com.sebialex.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.llm.CustomReasoning
import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.ReasoningSupport
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.theme.Palette

/** One line on what detection found for a custom-provider model, e.g. "Detected: supported · Gemini models.list". */
internal fun reasoningStatusLine(detected: ModelReasoning, override: String?): String {
    val what = when (detected.support) {
        ReasoningSupport.SUPPORTED -> "supported"
        ReasoningSupport.UNSUPPORTED -> "not supported"
        ReasoningSupport.UNKNOWN -> "unknown"
    }
    val base = "Detected: $what" + if (detected.source.isNotBlank()) " \u00b7 ${detected.source}" else ""
    return when (override) {
        CustomReasoning.OVERRIDE_SUPPORTED -> "Manual: supported \u00b7 $base"
        CustomReasoning.OVERRIDE_UNSUPPORTED -> "Manual: not supported \u00b7 $base"
        else -> base
    }
}

/**
 * Settings > Models > Thinking support: for custom providers, what reasoning detection found per
 * model and a manual override (Detect / Supported / Not supported) where the provider publishes
 * nothing. The override is labelled as manual wherever it shows.
 */
@Composable
internal fun ThinkingSupportGroup(settings: SecureSettings, snap: SecureSettings.Snapshot) {
    val providers = snap.customProviders.filter { it.models.isNotEmpty() }
    if (providers.isEmpty()) return
    var tab by remember { mutableStateOf(providers.first().id) }
    val provider = providers.firstOrNull { it.id == tab } ?: providers.first()
    var expanded by remember { mutableStateOf<String?>(null) }
    var showAll by remember(provider.id) { mutableStateOf(false) }
    SettingsGroup("Thinking support", help = "Detected from each provider's model list and docs. Where nothing is published, tap a model and set it by hand.") {
        if (providers.size > 1) ChoiceChips(
            options = providers.map { it.id },
            selected = provider.id,
            label = { id -> providers.firstOrNull { it.id == id }?.name ?: id },
            onSelect = { tab = it; expanded = null },
        )
        CustomReasoning.compatibilityNote(provider.baseUrl)?.let { note -> Help(note, label = "${provider.name} limits") }
        // Unknown and overridden models first: those are the ones that need attention.
        val rows = provider.models.map { m ->
            val id = CustomProviders.modelId(provider.id, m)
            Triple(m, id, CustomReasoning.detect(id, provider, null))
        }.sortedBy { (_, id, d) -> if (snap.reasoningOverrides[id] != null || d.support == ReasoningSupport.UNKNOWN) 0 else 1 }
        val visible = if (showAll) rows else rows.take(12)
        Column {
            visible.forEach { (m, id, detected) ->
                val override = snap.reasoningOverrides[id]
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = if (expanded == id) null else id }.padding(vertical = 6.dp)) {
                    Text(m, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(reasoningStatusLine(detected, override), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2)
                }
                if (expanded == id) Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChoiceChips(
                        options = listOf("", CustomReasoning.OVERRIDE_SUPPORTED, CustomReasoning.OVERRIDE_UNSUPPORTED),
                        selected = override.orEmpty(),
                        label = { when (it) { "" -> "Detect"; CustomReasoning.OVERRIDE_SUPPORTED -> "Supported"; else -> "Not supported" } },
                        onSelect = { settings.setReasoningOverride(id, it.ifEmpty { null }) },
                    )
                }
            }
            if (rows.size > visible.size) Text(
                "Show all ${rows.size} models",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showAll = true }.padding(vertical = 8.dp),
            )
        }
    }
}
