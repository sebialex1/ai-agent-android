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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.sebialex.agent.data.local.ImageModel
import com.sebialex.agent.data.local.ImageModelStore
import com.sebialex.agent.data.local.ImageModels
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModel
import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.local.Modality
import com.sebialex.agent.data.local.ModelStatus
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.llm.MediaModelRef
import com.sebialex.agent.data.llm.MediaModels
import com.sebialex.agent.data.secure.SecureSettings
import java.io.File
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
internal fun OnDeviceSettings(
    settings: SecureSettings,
    store: ModelStore,
    engine: LocalEngine,
    /** Image models for the MediaPipe Image Generator (the Image tab). */
    imageStore: ImageModelStore,
    /** Opens the image-model picker: the Image tab leads to real generation, not a dead end. */
    onOpenImageModels: () -> Unit = {},
) {
    val snap by settings.state.collectAsStateWithLifecycle()
    val statuses by store.statuses.collectAsStateWithLifecycle()
    val imported by store.imported.collectAsStateWithLifecycle()
    val engineState by engine.state.collectAsStateWithLifecycle()
    val shared by store.sharedState.collectAsStateWithLifecycle()
    val imageStatuses by imageStore.statuses.collectAsStateWithLifecycle()
    val imageImported by imageStore.imported.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // Downloads finish in the background; pick up their result whenever the page is shown.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { store.refresh(); imageStore.refresh() }
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

    var tab by rememberSaveable { mutableStateOf(Modality.TEXT) }
    var detail by remember { mutableStateOf<LocalModel?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = imageStore.import(uri).fold(
                onSuccess = { "Imported ${it.name}." },
                onFailure = { "Import failed: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The search above the tab bar follows the tab: text models for Text, image models for
        // Image (a .litertlm search on the Image tab found nothing usable), none for Video.
        when (tab) {
            Modality.IMAGE -> HfImageSection(settings, snap) { message = it }
            Modality.TEXT -> HfSection(settings, snap, store) { message = it }
            Modality.VIDEO -> Unit
        }

        // What is loaded, plus one collapsed row for the device-level knobs. Per-model context
        // and sampling live on the model's own card, not here.
        SettingsGroup("Runtime") {
            EngineRow(engineState, snap.model, store, engine)
            ExpandRow(
                "Advanced",
                value = (if (snap.localBackend == SecureSettings.BACKEND_GPU) "GPU" else "CPU") +
                    " \u00b7 ${(snap.localContext + 512) / 1024}K" +
                    (if (snap.localSampler != null) " \u00b7 custom sampling" else ""),
            ) {
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
                RowDivider()
                StorageNote(store, shared, onPermission = { storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) })
            }
        }

        SettingsGroup("Models") {
            ChoiceChips(
                options = Modality.entries.toList(),
                selected = tab,
                label = { when (it) { Modality.TEXT -> "Text"; Modality.IMAGE -> "Image"; Modality.VIDEO -> "Video" } },
                onSelect = { tab = it },
            )
            when (tab) {
                Modality.TEXT -> {
                    // Grouped by the RAM they are recommended for (a recommendation, never a limit).
                    val groups = LocalModels.forModality(Modality.TEXT).groupBy { it.minRamGb }.toSortedMap()
                    groups.forEach { (tier, models) ->
                        MiniLabel(if (tier == 1) "Ultra-light \u00b7 1 GB RAM" else "From $tier GB RAM")
                        models.forEachIndexed { i, m ->
                            if (i > 0) RowDivider()
                            ModelItem(m, statuses, snap.model, ramGb, store, engine, settings, scope, context, onDetail = { detail = m }) { message = it }
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
                                onDetail = { detail = hf.toLocalModel() },
                            ) { message = it }
                        }
                    }
                    if (imported.isNotEmpty()) {
                        MiniLabel("Imported")
                        imported.forEachIndexed { i, m ->
                            if (i > 0) RowDivider()
                            ModelItem(m, statuses, snap.model, ramGb, store, engine, settings, scope, context, onDetail = { detail = m }) { message = it }
                        }
                    }
                    RowDivider()
                    NavRow("Import a .litertlm file", icon = com.sebialex.agent.R.drawable.ic_plus, onClick = { picker.launch(arrayOf("*/*")) }, chevron = false)
                }
                Modality.IMAGE -> ImageSection(
                    settings = settings,
                    store = imageStore,
                    statuses = imageStatuses,
                    hfModels = snap.hfImageModels,
                    importedModels = imageImported,
                    ramGb = ramGb,
                    onImport = { imagePicker.launch(arrayOf("application/zip", "*/*")) },
                    onMessage = { message = it },
                )
                Modality.VIDEO -> ModalityNote(
                    "No on-device video models",
                    "Video generation is too heavy for a phone. The agent makes videos through a Google Gemini provider instead: add one in Settings > Models, then ask it for a video.",
                )
            }
            message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 6.dp)) }
        }
        Spacer(Modifier.height(8.dp))
    }

    // One model's own settings: its context window and sampling, nothing else.
    detail?.let { m ->
        com.sebialex.agent.ui.components.AppSheet(
            onDismiss = { detail = null },
            title = m.name,
            subtitle = m.tagline,
        ) {
            Text("Context window", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
            ChoiceChips(
                options = SecureSettings.LOCAL_CONTEXTS,
                selected = settings.modelContext(m.id).coerceAtMost(m.maxContext),
                label = { "${(it + 512) / 1024}K" },
                onSelect = { settings.setModelContext(m.id, it) },
            )
            Text("Sampling", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(start = 10.dp, top = 10.dp))
            SamplerEditor(settings.modelSampler(m.id)) { settings.setModelSampler(m.id, it) }
        }
    }
}

/** What a modality tab shows when the runtime cannot run it: the reason, and a way forward. */
@Composable
private fun ModalityNote(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        Text(body, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        action?.let { (label, onClick) ->
            PillButton(label, onClick = onClick, primary = true, modifier = Modifier.padding(top = 4.dp))
        }
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
private fun SamplerEditor(current: com.sebialex.agent.data.local.SamplerSpec?, onChange: (com.sebialex.agent.data.local.SamplerSpec?) -> Unit) {
    val spec = current ?: com.sebialex.agent.data.local.SamplerSpec(temperature = 0.8f, topK = 64, topP = 0.95f, seed = 0)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SamplerSlider("Temperature", spec.temperature, 0f..2f, 0.05f) { onChange(spec.copy(temperature = it)) }
        SamplerSlider("Top-K", spec.topK.toFloat(), 1f..128f, 1f) { onChange(spec.copy(topK = it.toInt())) }
        SamplerSlider("Top-P", spec.topP, 0.05f..1f, 0.05f) { onChange(spec.copy(topP = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Seed", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
            PlainField("", spec.seed.toString(), "0") { onChange(spec.copy(seed = it.toIntOrNull() ?: 0)) }
        }
        PillButton("Reset", onClick = { onChange(null) })
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
    onDetail: (() -> Unit)? = null,
    onMessage: (String?) -> Unit,
) {
    ModelRow(
        model = m,
        status = statuses[m.id] ?: ModelStatus.Missing,
        selected = selectedId == m.id,
        ramGb = ramGb,
        removable = removable,
        onDetail = onDetail,
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
    onDetail: (() -> Unit)? = null,
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
            if (onDetail != null && status is ModelStatus.Ready) QuietIconButton(
                R.drawable.ic_settings, "Settings for ${model.name}", onClick = onDetail,
                tint = Palette.IconMuted, size = 30.dp, iconSize = 14.dp,
            )
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

/**
 * The Image tab: Stable-Diffusion-1.5 models for the phone's own MediaPipe Image Generator.
 * A model is a zip that unpacks into a directory of weight bins + the tokenizer; when ready it
 * can be made the generate_image target (Settings > Models stays the place for cloud pickers).
 */
@Composable
private fun ImageSection(
    settings: SecureSettings,
    store: ImageModelStore,
    statuses: Map<String, ModelStatus>,
    /** Entries added from the Hugging Face image search above; deleted with the trash button. */
    hfModels: List<ImageModel>,
    importedModels: List<ImageModel>,
    ramGb: Int,
    onImport: () -> Unit,
    onMessage: (String?) -> Unit,
) {
    // Fresh on every entry into the tab; picking anywhere else (Settings > Models) shows up too.
    var selected by remember { mutableStateOf(settings.mediaImageModel()) }
    val context = LocalContext.current

    Text(
        "Runs the picture entirely on this phone: model weights stay on the device, nothing is sent anywhere. " +
            "The engine only fits Stable Diffusion 1.5 architecture models, the picture comes out square, and " +
            "generating takes a while (\u2248 30\u201360 s) on \u2265 8 GB RAM phones. The engine runs in its own process: " +
            "if this phone's GPU cannot run it, the app reports the failure instead of crashing.",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextTertiary,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    fun choose(m: ImageModel) {
        selected = MediaModelRef(MediaModels.PROVIDER_DEVICE, m.id)
        settings.setMediaImageModel(selected)
    }

    ImageModels.all.forEachIndexed { i, m ->
        if (i > 0) RowDivider()
        ImageRow(
            model = m,
            status = statuses[m.id] ?: ModelStatus.Missing,
            ramGb = ramGb,
            selected = selected.provider == MediaModels.PROVIDER_DEVICE && selected.model == m.id,
            onDownload = { onMessage(store.download(m)) },
            onCancel = { store.cancel(m) },
            onUse = { choose(m) },
            onDelete = {
                if (selected.provider == MediaModels.PROVIDER_DEVICE && selected.model == m.id) {
                    // Deleting the model in use would leave generate_image pointing at nothing.
                    onMessage("This model is currently used for image generation. Pick another image model first, then delete it.")
                } else {
                    store.delete(m)
                }
            },
            onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        )
    }
    if (hfModels.isNotEmpty()) {
        MiniLabel("From Hugging Face")
        hfModels.forEachIndexed { i, m ->
            if (i > 0) RowDivider()
            ImageRow(
                model = m,
                status = statuses[m.id] ?: ModelStatus.Missing,
                ramGb = ramGb,
                selected = selected.provider == MediaModels.PROVIDER_DEVICE && selected.model == m.id,
                onDownload = { onMessage(store.download(m)) },
                onCancel = { store.cancel(m) },
                onUse = { choose(m) },
                onDelete = {
                    if (selected.provider == MediaModels.PROVIDER_DEVICE && selected.model == m.id) {
                        onMessage("This model is currently used for image generation. Pick another image model first, then delete it.")
                    } else {
                        store.delete(m)
                        settings.removeHfImageModel(m.id)
                    }
                },
                onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            )
        }
    }
    if (importedModels.isNotEmpty()) {
        MiniLabel("Imported")
        importedModels.forEachIndexed { i, m ->
            if (i > 0) RowDivider()
            ImageRow(
                model = m,
                status = statuses[m.id] ?: ModelStatus.Missing,
                ramGb = ramGb,
                selected = selected.provider == MediaModels.PROVIDER_DEVICE && selected.model == m.id,
                onDownload = {},
                onCancel = { store.cancel(m) },
                onUse = { choose(m) },
                onDelete = { store.delete(m) },
                onOpenPage = {},
            )
        }
    }
    RowDivider()
    Text(
        "Steps per picture",
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextSecondary,
        modifier = Modifier.padding(start = 10.dp, top = 10.dp),
    )
    ChoiceChips(
        options = SecureSettings.IMAGE_STEPS,
        selected = settings.imageSteps(),
        label = { "$it" },
        onSelect = { settings.setImageSteps(it) },
    )
    Text(
        "Fewer steps finish sooner, the picture gets rougher; more steps are slower and cleaner. The step counter in the chat shows the progress either way.",
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextTertiary,
        modifier = Modifier.padding(start = 10.dp, top = 2.dp),
    )
    RowDivider()
    NavRow("Import an image model .zip", icon = R.drawable.ic_plus, onClick = onImport, chevron = false)
    val free = runCatching { store.freeBytes() }.getOrDefault(0L)
    if (free > 0) MiniLabel("Saved in ${store.root.parentFile?.name}/${store.root.name} \u00b7 ${LocalModels.formatSize(free)} free")
}

/** One image model as a dense row: name + facts left, at most one action right, progress below. */
@Composable
private fun ImageRow(
    model: ImageModel,
    status: ModelStatus,
    ramGb: Int,
    selected: Boolean,
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
                    Text(model.tagline, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2)
                    val facts = buildList {
                        if (model.sizeBytes > 0) add(LocalModels.formatSize(model.sizeBytes))
                        if (model.ramTiersGb.isNotEmpty()) add(tierText(model.ramTiersGb) + " RAM")
                        if (model.license.isNotEmpty()) add(model.license)
                    }
                    if (facts.isNotEmpty()) Text(facts.joinToString(" \u00b7 "), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1)
                }
            }
            if (selected) AppIcon(R.drawable.ic_check, "Used by generate_image", tint = Palette.IconActive, size = 15.dp)
            if (status is ModelStatus.Ready) QuietIconButton(
                R.drawable.ic_trash, "Delete ${model.name}", onClick = onDelete,
                tint = Palette.IconMuted, size = 30.dp, iconSize = 14.dp,
            )
            when (status) {
                is ModelStatus.Downloading -> PillButton("Cancel", onClick = onCancel)
                is ModelStatus.Importing -> Unit
                is ModelStatus.Ready -> if (!selected) PillButton("Use", onClick = onUse, primary = true)
                is ModelStatus.Failed -> if (model.url != null || model.parts.isNotEmpty()) PillButton("Retry", onClick = onDownload)
                ModelStatus.Missing -> if (model.url != null || model.parts.isNotEmpty()) PillButton("Get", onClick = onDownload, primary = true)
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
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Palette.Accent,
                    trackColor = Palette.SurfaceHigh,
                )
                Text("Unpacking the model\u2026", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(top = 1.dp, bottom = 2.dp))
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
