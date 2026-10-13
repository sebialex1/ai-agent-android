package com.sebialex.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.llm.OllamaModel
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.local.ModelStatus
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/** Where a model runs. Cloud providers and this phone are kept strictly apart in the picker. */
internal enum class ModelSource(val label: String) {
    OPENROUTER("OpenRouter"),
    OLLAMA("Ollama Cloud"),
    DEVICE("On this phone");

    companion object {
        fun of(model: String): ModelSource = when {
            LocalModels.isLocal(model) -> DEVICE
            OllamaCloud.isOllama(model) -> OLLAMA
            else -> OPENROUTER
        }
    }
}

/** One-line description of where [model] runs, shown under its name. */
internal fun sourceLine(model: String, backend: String): String = when (ModelSource.of(model)) {
    ModelSource.DEVICE -> "On this phone \u00b7 ${if (backend == SecureSettings.BACKEND_CPU) "CPU" else "GPU"}"
    ModelSource.OLLAMA -> "Ollama Cloud"
    ModelSource.OPENROUTER -> "OpenRouter"
}

/** Human name for any model id: catalog name, Ollama name without prefix, or the OpenRouter id. */
internal fun modelName(model: String): String =
    if (OllamaCloud.isOllama(model)) OllamaCloud.wireName(model) else LocalModels.displayName(model)

/** The "Model" row in Settings: current model and where it runs; opens the picker. */
@Composable
internal fun ModelPickerRow(model: String, backend: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Model")
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(modelName(model), style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sourceLine(model, backend), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            }
            AppIcon(R.drawable.ic_chevron_down, "Choose model", tint = Palette.IconMuted, size = 18.dp)
        }
    }
}

private sealed interface OllamaList {
    data object Loading : OllamaList
    data class Ready(val models: List<OllamaModel>) : OllamaList
    data class Failed(val message: String) : OllamaList
}

/**
 * Model picker grouped by source with a segmented switch (OpenRouter / Ollama Cloud / On this
 * phone), so the three never mix in one long list. Picking a cloud model frees the on-device
 * model's memory; picking an on-device model starts loading it right away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    settings: SecureSettings,
    ollama: OllamaCloud,
    store: ModelStore,
    engine: LocalEngine,
    onOpenOnDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val snap by settings.state.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf(ModelSource.of(snap.model)) }
    fun pickCloud(id: String) {
        settings.setModel(id)
        engine.unloadAsync()
        onDismiss()
    }
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Choose a model", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 4.dp))
            SegmentedText(
                options = ModelSource.entries,
                selected = source,
                label = { it.label },
                onSelect = { source = it },
                modifier = Modifier.fillMaxWidth(),
                fill = true,
            )
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (source) {
                    ModelSource.OPENROUTER -> OpenRouterSection(snap, onPick = ::pickCloud)
                    ModelSource.OLLAMA -> OllamaSection(snap, ollama, onPick = ::pickCloud, onSaveCustom = settings::setOllamaCustomModel)
                    ModelSource.DEVICE -> DeviceSection(
                        snap = snap,
                        store = store,
                        onPick = { m ->
                            settings.setModel(m.id)
                            engine.preload(m)
                            onDismiss()
                        },
                        onOpenOnDevice = { onDismiss(); onOpenOnDevice() },
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenRouterSection(snap: SecureSettings.Snapshot, onPick: (String) -> Unit) {
    if (!snap.hasOpenRouterKey) EmptyNote("Add your OpenRouter API key in General to use these models.")
    val suggestions = listOf(SecureSettings.DEFAULT_MODEL, snap.remoteModel).distinct()
    suggestions.forEach { id ->
        PickRow(
            title = id,
            subtitle = if (id == SecureSettings.DEFAULT_MODEL) "Picks a suitable model per request" else "Last used",
            selected = snap.model == id,
            onClick = { onPick(id) },
        )
    }
    Spacer(Modifier.size(6.dp))
    ModelIdField(
        placeholder = "Any OpenRouter model id, e.g. anthropic/claude-sonnet-4.5",
        onSubmit = { id -> if (id.isNotBlank() && !LocalModels.isLocal(id) && !OllamaCloud.isOllama(id)) onPick(id) },
    )
}

@Composable
private fun OllamaSection(
    snap: SecureSettings.Snapshot,
    ollama: OllamaCloud,
    onPick: (String) -> Unit,
    onSaveCustom: (String) -> Unit,
) {
    if (!snap.hasOllamaKey) {
        EmptyNote("Add your Ollama Cloud API key in General to use these models. Keys are created at ollama.com/settings/keys.")
    }
    // A hand-typed model id always works, even when the live list fails, is empty or misses it.
    val custom = snap.ollamaCustomModel.takeIf { it.isNotBlank() }?.let { OllamaCloud.PREFIX + it }
    if (custom != null) {
        PickRow(
            title = OllamaCloud.wireName(custom),
            subtitle = "Custom model ID",
            selected = snap.model == custom,
            onClick = { onPick(custom) },
        )
    }
    ModelIdField(
        placeholder = "Custom model ID, e.g. gpt-oss:120b",
        initial = snap.ollamaCustomModel,
        onSubmit = { raw ->
            val name = OllamaCloud.normalizeName(raw)
            onSaveCustom(name)
            if (name.isNotEmpty()) onPick(OllamaCloud.PREFIX + name)
        },
    )
    Text(
        "Use the exact name from ollama.com (without the -cloud suffix). Leave empty to use only the list below.",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextTertiary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
    if (!snap.hasOllamaKey) return

    var attempt by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<OllamaList>(OllamaList.Loading) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(attempt, snap.ollamaHint) {
        list = OllamaList.Loading
        list = try {
            OllamaList.Ready(ollama.models(refresh = attempt > 0))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            OllamaList.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    when (val l = list) {
        OllamaList.Loading -> EmptyNote("Loading Ollama Cloud models\u2026")
        is OllamaList.Failed -> {
            EmptyNote("Could not load the model list: ${l.message}. You can still enter a model ID above.")
            PickRow(title = "Try again", subtitle = null, selected = false, onClick = { attempt++ })
        }
        is OllamaList.Ready -> {
            if (l.models.isEmpty()) EmptyNote("The list came back empty. Enter a model ID above.")
            // The last used model stays reachable even when the list does not include it.
            val last = snap.ollamaModel.takeIf { it.isNotEmpty() && it != custom && l.models.none { m -> m.id == it } }
            if (last != null) PickRow(title = OllamaCloud.wireName(last), subtitle = "Last used", selected = snap.model == last, onClick = { onPick(last) })
            if (l.models.size > 8) {
                ModelIdField(placeholder = "Filter models", onSubmit = {}, onChange = { filter = it }, submit = false)
                Spacer(Modifier.size(4.dp))
            }
            l.models.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }.forEach { m ->
                PickRow(
                    title = m.name,
                    subtitle = if (snap.ollamaModel == m.id && snap.model != m.id) "Last used" else null,
                    selected = snap.model == m.id,
                    onClick = { onPick(m.id) },
                )
            }
        }
    }
}

@Composable
private fun DeviceSection(
    snap: SecureSettings.Snapshot,
    store: ModelStore,
    onPick: (com.sebialex.agent.data.local.LocalModel) -> Unit,
    onOpenOnDevice: () -> Unit,
) {
    val statuses by store.statuses.collectAsStateWithLifecycle()
    val imported by store.imported.collectAsStateWithLifecycle()
    (LocalModels.catalog + imported).forEach { m ->
        val status = statuses[m.id] ?: ModelStatus.Missing
        val ready = status is ModelStatus.Ready
        PickRow(
            title = m.name,
            subtitle = when (status) {
                is ModelStatus.Ready -> "Downloaded \u00b7 runs on the ${if (snap.localBackend == SecureSettings.BACKEND_CPU) "CPU" else "GPU"}"
                is ModelStatus.Downloading -> "Downloading \u00b7 ${(status.fraction * 100).toInt()}%"
                is ModelStatus.Importing -> if (status.moving) "Moving to Downloads\u2026" else "Importing\u2026"
                is ModelStatus.Failed -> "Download failed \u00b7 open the On-device tab"
                ModelStatus.Missing -> "Not downloaded \u00b7 ${LocalModels.formatSize(m.sizeBytes)} \u00b7 get it in the On-device tab"
            },
            selected = snap.model == m.id,
            enabled = ready,
            onClick = { if (ready) onPick(m) else onOpenOnDevice() },
        )
    }
    PickRow(title = "Manage on-device models", subtitle = "Download, import, accelerator and memory", selected = false, onClick = onOpenOnDevice)
}

@Composable
private fun PickRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.SurfaceHigh else Palette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Palette.TextPrimary else Palette.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            AppIcon(R.drawable.ic_check, "Selected", tint = Palette.IconActive, size = 18.dp)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun ModelIdField(
    placeholder: String,
    onSubmit: (String) -> Unit,
    onChange: (String) -> Unit = {},
    submit: Boolean = true,
    initial: String = "",
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(16.dp), Palette.SurfaceHigh, elevation = 2.dp)
            .heightIn(min = 48.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.trim(); onChange(draft) },
                singleLine = true,
                textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text(placeholder, style = MonoSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                },
            )
        }
        // A prefilled value can be cleared and saved empty, which removes it.
        if (submit && (draft.isNotEmpty() || initial.isNotEmpty()) && draft != initial) {
            com.sebialex.agent.ui.components.QuietIconButton(R.drawable.ic_check, "Use $draft", onClick = { onSubmit(draft) })
        }
    }
}
