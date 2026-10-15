package com.sebialex.agent.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sebialex.agent.R
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppSheet

/** "Same as chat model", one model's name, or "3 models · first is the default". */
internal fun subagentModelsLine(models: List<String>): String = when (models.size) {
    0 -> "Same as the chat model"
    1 -> modelName(models.first())
    else -> "${modelName(models.first())} + ${models.size - 1} more"
}

/**
 * Settings > Models > Subagents: whether the agent may start subagents, and which models they
 * can run on. The models are picked in the same list as the chat model (several at once; the
 * first picked is the default), without on-device models.
 */
@Composable
internal fun SubagentGroup(
    settings: SecureSettings,
    snap: SecureSettings.Snapshot,
    models: OpenRouterModels,
    ollama: OllamaCloud,
    store: ModelStore,
    engine: LocalEngine,
) {
    var picking by remember { mutableStateOf(false) }
    SettingsGroup(
        "Subagents",
        help = "The agent can hand bounded pieces of work to subagents that run in parallel and report back. Each shows as a small " +
            "card under the answer. Pick the models they may use; with none picked they use the chat model. On-device models are not offered.",
    ) {
        SwitchRow(title = "Allow subagents", subtitle = null, checked = snap.subagentsOn, onChange = settings::setSubagentsOn)
        if (snap.subagentsOn) NavRow(
            title = "Subagent models",
            value = subagentModelsLine(snap.subagentModels),
            icon = R.drawable.ic_code,
            onClick = { picking = true },
        )
    }
    if (picking) AppSheet(onDismiss = { picking = false }, title = "Subagent models", subtitle = "Tap to add or remove. The first one is the default.") {
        ModelList(
            settings, models, ollama, store, engine,
            onPicked = {},
            onOpenOnDevice = {},
            chosen = snap.subagentModels.toSet(),
            onToggle = settings::toggleSubagentModel,
        )
    }
}
