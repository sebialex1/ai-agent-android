package com.sebialex.agent.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.local.ACTIVATION_TYPES
import com.sebialex.agent.data.local.EngineState
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModel
import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.local.ModelStatus
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.launch

/**
 * "On-device" page of Settings: models that run on this phone's GPU with nothing sent anywhere.
 * Download, use, delete or import model files, see what is loaded and on which accelerator,
 * and tune accelerator, context size and multi-token prediction.
 */
@Composable
internal fun OnDeviceSettings(settings: SecureSettings, store: ModelStore, engine: LocalEngine) {
    val snap by settings.state.collectAsStateWithLifecycle()
    val statuses by store.statuses.collectAsStateWithLifecycle()
    val imported by store.imported.collectAsStateWithLifecycle()
    val engineState by engine.state.collectAsStateWithLifecycle()
    val shared by store.sharedState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // Downloads finish in the background; pick up their result whenever the page is shown.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { store.refresh() }
    val ramGb = remember { totalRamGb(context) }
    val freeBytes = remember(statuses) { store.freeBytes() }
    // Android 8-10: Downloads needs the storage permission; granting it moves models there.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { store.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = store.import(uri).fold(
                onSuccess = { "Imported ${it.name}." },
                onFailure = { "Import failed: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HfSection(settings, snap, store) { message = it }

        SettingsGroup("Runtime") {
            NavRow(
                title = if (ramGb > 0) "$ramGb GB RAM" else "RAM unknown",
                value = "${LocalModels.formatSize(freeBytes)} free",
            )
            EngineRow(engineState, snap.model, store, engine)
            RowDivider()
            Text("Accelerator", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            ChoiceChips(
                options = listOf(SecureSettings.BACKEND_GPU, SecureSettings.BACKEND_CPU),
                selected = snap.localBackend,
                label = { if (it == SecureSettings.BACKEND_GPU) "GPU" else "CPU" },
                onSelect = settings::setLocalBackend,
            )
            Text("Context window", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            ChoiceChips(
                options = SecureSettings.LOCAL_CONTEXTS,
                selected = snap.localContext,
                label = { "${(it + 512) / 1024}K" },
                onSelect = settings::setLocalContext,
            )
            Text("Activation", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            ChoiceChips(
                options = ACTIVATION_TYPES,
                selected = ACTIVATION_TYPES.first { it.first == snap.localActivation },
                label = { it.second },
                onSelect = { settings.setLocalActivation(it.first) },
            )
            SwitchRow(
                title = "Faster decoding (MTP)",
                checked = snap.localSpeculative,
                onChange = settings::setLocalSpeculative,
            )
            ExpandRow(
                "Sampling",
                value = if (snap.localSampler != null) "Custom" else "Model default",
            ) { SamplerEditor(settings, snap.localSampler) }
            RowDivider()
            StorageNote(store, shared, onPermission = { storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) })
        }

        SettingsGroup("Models") {
            // Grouped by the RAM they are recommended for (a recommendation, never a limit).
            val groups = LocalModels.catalog.groupBy { it.minRamGb }.toSortedMap()
            groups.forEach { (tier, models) ->
                MiniLabel(if (tier == 1) "Ultra-light \u00b7 1 GB RAM" else "From $tier GB RAM")
                models.forEachIndexed { i, m ->
                    if (i > 0) RowDivider()
                    ModelItem(m, statuses, snap.model, ramGb, store, engine, settings, scope, context) { message = it }
                }
            }
            if (snap.hfModels.isNotEmpty()) {
                MiniLabel("From Hugging Face")
                snap.hfModels.forEachIndexed { i, hf ->
                    if (i > 0) RowDivider()
                    ModelItem(
                        hf.toLocalModel(), statuses, snap.model, ramGb, store, engine, settings, scope, context,
                        removable = true,
                        afterDelete = { settings.removeHfModel(hf.id) },
                    ) { message = it }
                }
            }
            MiniLabel("Imported")
            imported.forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                ModelItem(m, statuses, snap.model, ramGb, store, engine, settings, scope, context) { message = it }
            }
            RowDivider()
            NavRow("Import a .litertlm file", icon = com.sebialex.agent.R.drawable.ic_plus, onClick = { picker.launch(arrayOf("*/*")) }, chevron = false)
            message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 6.dp)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A small in-card section label, so all models share one card. */
@Composable
private fun MiniLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextTertiary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Temperature, top-K, top-P and seed; Reset returns the model to its runtime defaults. */
@Composable
private fun SamplerEditor(settings: SecureSettings, current: com.sebialex.agent.data.local.SamplerSpec?) {
    val spec = current ?: com.sebialex.agent.data.local.SamplerSpec(temperature = 0.8f, topK = 64, topP = 0.95f, seed = 0)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SamplerSlider("Temperature", spec.temperature, 0f..2f, 0.05f) { settings.setLocalSampler(spec.copy(temperature = it)) }
        SamplerSlider("Top-K", spec.topK.toFloat(), 1f..128f, 1f) { settings.setLocalSampler(spec.copy(topK = it.toInt())) }
        SamplerSlider("Top-P", spec.topP, 0.05f..1f, 0.05f) { settings.setLocalSampler(spec.copy(topP = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Seed", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
            PlainField("", spec.seed.toString(), "0") { settings.setLocalSampler(spec.copy(seed = it.toIntOrNull() ?: 0)) }
        }
        PillButton("Reset", onClick = { settings.setLocalSampler(null) })
    }
}

@Composable
private fun SamplerSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange((it / step).toInt() * step) },
            valueRange = range,
        )
    }
}

/** Where model files are kept, and what to grant so they survive reinstalls. */
@Composable
private fun StorageNote(store: ModelStore, shared: Boolean, onPermission: () -> Unit) {
    val context = LocalContext.current
    Text(
        if (shared) "Saved in ${store.location}"
        else "Saved in ${store.location} (deleted with the app)",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextTertiary,
    )
    when {
        store.needsStoragePermission -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Allow storage access to keep models in Downloads instead.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            PillButton("Allow", onClick = onPermission)
        }
        shared && store.needsAllFilesAccessForOldFiles -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "After a reinstall, Android hides files the old install downloaded until All files access is allowed.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            PillButton("Allow", onClick = {
                val ok = runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                }.isSuccess
                if (!ok) runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            })
        }
    }
}

/** What is in memory right now, with load/unload. */
@Composable
private fun EngineRow(state: EngineState, selectedModel: String, store: ModelStore, engine: LocalEngine) {
    val scope = rememberCoroutineScope()
    val selected = LocalModels.resolve(selectedModel)
    val (title, detail) = when (state) {
        EngineState.Idle -> "No model loaded" to (if (selected != null) "${selected.name} loads with the first message." else "Pick an on-device model to use it in chats.")
        is EngineState.Loading -> "Loading ${state.name}\u2026" to "On the ${state.backend}. Large models take a few seconds."
        is EngineState.Ready -> "${state.model.name} ready" to buildString {
            append("On the ${state.model.backend} \u00b7 ${state.model.contextTokens / 1024}K context")
            if (state.model.speculative) append(" \u00b7 MTP")
            append(" \u00b7 loaded in ${"%.1f".format(state.model.loadMillis / 1000.0)} s")
            state.model.fallbackReason?.let { append("\nGPU not available ($it), running on the CPU.") }
        }
        is EngineState.Failed -> "${state.name} could not load" to state.message
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = if (state is EngineState.Failed) Palette.Danger else Palette.TextTertiary)
        }
        Spacer(Modifier.width(8.dp))
        when {
            state is EngineState.Ready -> PillButton("Unload", onClick = { engine.unloadAsync() })
            state is EngineState.Loading -> Unit
            selected != null && store.fileFor(selected) != null ->
                PillButton(if (state is EngineState.Failed) "Retry" else "Load", onClick = { scope.launch { runCatching { engine.load(selected) } } })
        }
    }
}

@Composable
private fun ModelItem(
    m: LocalModel,
    statuses: Map<String, ModelStatus>,
    selectedId: String,
    ramGb: Int,
    store: ModelStore,
    engine: LocalEngine,
    settings: SecureSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    removable: Boolean = false,
    afterDelete: () -> Unit = {},
    onMessage: (String?) -> Unit,
) {
    ModelRow(
        model = m,
        status = statuses[m.id] ?: ModelStatus.Missing,
        selected = selectedId == m.id,
        ramGb = ramGb,
        removable = removable,
        onDownload = { onMessage(store.download(m)) },
        onCancel = { store.cancel(m) },
        onUse = {
            settings.setModel(m.id)
            engine.preload(m)
        },
        onDelete = {
            scope.launch {
                engine.unloadIf(m.id)
                store.delete(m)
                afterDelete()
                if (settings.model() == m.id) settings.setModel(settings.cloudModel())
            }
        },
        onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
    )
}

/** RAM tiers as a short range, e.g. "4\u20137 GB". */
private fun tierText(tiers: List<Int>): String = when {
    tiers.isEmpty() -> ""
    tiers.min() == tiers.max() -> "${tiers.min()} GB"
    else -> "${tiers.min()}\u2013${tiers.max()} GB"
}

/**
 * One model as a dense list row: name with a small facts line on the left, at most one action
 * on the right, and extra lines only while downloading, importing or failing. Tapping the name
 * opens the model card; the trash icon deletes the file (and, for added Hugging Face models,
 * removes them from the list).
 */
@Composable
private fun ModelRow(
    model: LocalModel,
    status: ModelStatus,
    selected: Boolean,
    ramGb: Int,
    removable: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            val page = model.pageUrl
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (page != null) Modifier.clickable(onClickLabel = "Open the model card") { onOpenPage(page) } else Modifier)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, maxLines = 1)
                        if (page != null) {
                            Spacer(Modifier.width(4.dp))
                            AppIcon(R.drawable.ic_open_external, null, tint = Palette.IconMuted, size = 12.dp)
                        }
                    }
                    val size = (status as? ModelStatus.Ready)?.bytes ?: model.sizeBytes
                    val facts = buildList {
                        if (size > 0) add(LocalModels.formatSize(size))
                        if (model.ramTiersGb.isNotEmpty()) add(tierText(model.ramTiersGb) + " RAM")
                        if (model.toolCalling) add("Tools")
                        if (model.thinking) add("Thinking")
                        if (!model.imported) add("${model.maxContext / 1000}K context")
                        if (model.license.isNotEmpty()) add(model.license)
                    }
                    if (facts.isNotEmpty()) Text(facts.joinToString(" \u00b7 "), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1)
                }
            }
            if (selected) AppIcon(R.drawable.ic_check, "In use", tint = Palette.IconActive, size = 15.dp)
            if (status is ModelStatus.Ready || removable) QuietIconButton(
                R.drawable.ic_trash, "Delete ${model.name}", onClick = onDelete,
                tint = Palette.IconMuted, size = 30.dp, iconSize = 14.dp,
            )
            when (status) {
                is ModelStatus.Downloading -> PillButton("Cancel", onClick = onCancel)
                is ModelStatus.Ready -> if (!selected) PillButton("Use", onClick = onUse, primary = true)
                is ModelStatus.Failed -> if (model.url != null) PillButton("Retry", onClick = onDownload)
                is ModelStatus.Importing -> Unit
                ModelStatus.Missing -> if (model.url != null) PillButton("Get", onClick = onDownload, primary = true)
            }
        }
        when (status) {
            is ModelStatus.Downloading -> {
                LinearProgressIndicator(
                    progress = { status.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
                val progress = if (status.total > 0) "${LocalModels.formatSize(status.done)} of ${LocalModels.formatSize(status.total)}" else "Starting\u2026"
                Text(status.waiting ?: progress, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(top = 1.dp, bottom = 2.dp))
            }
            is ModelStatus.Importing -> {
                LinearProgressIndicator(
                    progress = { status.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
                Text(
                    if (status.moving) "Moving to Downloads\u2026 ${LocalModels.formatSize(status.done)} of ${LocalModels.formatSize(status.total)}" else "Importing\u2026",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                    modifier = Modifier.padding(top = 1.dp, bottom = 2.dp),
                )
            }
            is ModelStatus.Failed -> Text(status.message, style = MaterialTheme.typography.labelSmall, color = Palette.Danger)
            else -> Unit
        }
        if (model.minRamGb > 0 && ramGb in 1 until model.minRamGb) {
            Text(
                "Needs ${model.minRamGb}+ GB RAM (this phone has $ramGb GB)",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Warning,
            )
        }
    }
}

private fun totalRamGb(context: Context): Int = runCatching {
    val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching 0
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    // Phones report a little less than their nominal RAM (e.g. 7.4 GB for 8 GB), so round up.
    kotlin.math.ceil(info.totalMem / 1_073_741_824.0).toInt()
}.getOrDefault(0)
