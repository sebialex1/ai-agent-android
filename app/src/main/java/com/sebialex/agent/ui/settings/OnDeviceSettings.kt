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
import com.sebialex.agent.data.local.EngineState
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModel
import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.local.ModelStatus
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.launch

/**
 * "On-device" tab of Settings: models that run on this phone's GPU with nothing sent anywhere.
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
    // Downloads finish in the background; pick up their result whenever the tab is shown.
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

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Models that run on this phone's GPU. Chats with them never leave the device and work offline; web tools still need a connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
            )
            Text(
                "This phone: ${if (ramGb > 0) "$ramGb GB RAM" else "RAM unknown"} \u00b7 ${LocalModels.formatSize(freeBytes)} free",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
            )
            StorageNote(store, shared, onPermission = { storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) })
        }

        EngineRow(engineState, snap.model, store, engine)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Models")
            (LocalModels.catalog + imported).forEach { m ->
                ModelCard(
                    model = m,
                    status = statuses[m.id] ?: ModelStatus.Missing,
                    selected = snap.model == m.id,
                    ramGb = ramGb,
                    onDownload = { message = store.download(m) },
                    onCancel = { store.cancel(m) },
                    onUse = {
                        settings.setModel(m.id)
                        engine.preload(m)
                    },
                    onDelete = {
                        scope.launch {
                            engine.unloadIf(m.id)
                            store.delete(m)
                            if (settings.model() == m.id) settings.setModel(settings.cloudModel())
                        }
                    },
                    onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                )
            }
            Text(
                "Import a .litertlm file",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { picker.launch(arrayOf("*/*")) }
                    .padding(vertical = 8.dp),
            )
            message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Accelerator")
            ChoiceChips(
                options = listOf(SecureSettings.BACKEND_GPU, SecureSettings.BACKEND_CPU),
                selected = snap.localBackend,
                label = { if (it == SecureSettings.BACKEND_GPU) "GPU" else "CPU" },
                onSelect = settings::setLocalBackend,
            )
            Text(
                "GPU is much faster. If the GPU cannot load the model, the CPU is used automatically. Applies at the next load.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Context window")
            ChoiceChips(
                options = SecureSettings.LOCAL_CONTEXTS,
                selected = snap.localContext,
                label = { "${(it + 512) / 1024}K" },
                onSelect = settings::setLocalContext,
            )
            Text(
                "How much of the conversation and tool output the model sees. Larger windows use more memory and load slower. Capped by each model's own limit.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
            )
        }

        SwitchRow(
            title = "Faster decoding (MTP)",
            subtitle = "Multi-token prediction for models that include it (Gemma 4), on the GPU. Applies at the next load.",
            checked = snap.localSpeculative,
            onChange = settings::setLocalSpeculative,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Where model files are kept, and what to grant so they survive reinstalls. */
@Composable
private fun StorageNote(store: ModelStore, shared: Boolean, onPermission: () -> Unit) {
    val context = LocalContext.current
    Text(
        if (shared) "Models are saved in ${store.location}, so they stay when app data is cleared or the app is reinstalled."
        else "Models are saved in ${store.location} and are deleted with the app.",
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
            .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 2.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_shield, null, tint = Palette.IconMuted, size = 18.dp)
        Spacer(Modifier.width(12.dp))
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
private fun ModelCard(
    model: LocalModel,
    status: ModelStatus,
    selected: Boolean,
    ramGb: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(18.dp), if (selected) Palette.SurfaceHighest else Palette.SurfaceHigh, elevation = 2.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.name, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            val size = (status as? ModelStatus.Ready)?.bytes ?: model.sizeBytes
            if (size > 0) Text(LocalModels.formatSize(size), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        Text(model.tagline, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
        val tags = buildList {
            if (model.recommended) add("Recommended")
            if (model.toolCalling) add("Tools")
            if (model.thinking) add("Thinking")
            if (!model.imported) add("${model.maxContext / 1000}K max context")
        }
        if (tags.isNotEmpty()) Text(tags.joinToString(" \u00b7 "), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        if (model.minRamGb > 0 && ramGb in 1 until model.minRamGb) {
            Text(
                "Needs about ${model.minRamGb} GB RAM; this phone has $ramGb GB, so it may fail to load or run slowly.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Warning,
            )
        }
        when (status) {
            is ModelStatus.Downloading -> {
                LinearProgressIndicator(
                    progress = { status.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Palette.Accent,
                    trackColor = Palette.Surface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val progress = if (status.total > 0) "${LocalModels.formatSize(status.done)} of ${LocalModels.formatSize(status.total)}" else "Starting\u2026"
                    Text(status.waiting ?: progress, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.weight(1f))
                    PillButton("Cancel", onClick = onCancel)
                }
            }
            is ModelStatus.Importing -> {
                LinearProgressIndicator(
                    progress = { status.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Palette.Accent,
                    trackColor = Palette.Surface,
                )
                Text(
                    if (status.moving) "Moving to Downloads\u2026 ${LocalModels.formatSize(status.done)} of ${LocalModels.formatSize(status.total)}" else "Importing\u2026",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                )
            }
            is ModelStatus.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Text(
                        "In use",
                        style = MaterialTheme.typography.labelLarge,
                        color = Palette.TextSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                } else {
                    PillButton("Use", onClick = onUse, primary = true)
                }
                PillButton("Delete", onClick = onDelete, danger = true)
            }
            is ModelStatus.Failed -> {
                Text(status.message, style = MaterialTheme.typography.labelSmall, color = Palette.Danger)
                if (model.url != null) PillButton("Try again", onClick = onDownload)
            }
            ModelStatus.Missing -> if (model.url != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Download \u00b7 ${LocalModels.formatSize(model.sizeBytes)}", onClick = onDownload, primary = true)
                    model.pageUrl?.let { url -> PillButton("Model page", onClick = { onOpenPage(url) }) }
                }
            }
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
