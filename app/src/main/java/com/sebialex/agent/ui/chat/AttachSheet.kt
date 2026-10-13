package com.sebialex.agent.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.SheetOption
import com.sebialex.agent.ui.settings.ModelList
import com.sebialex.agent.ui.settings.modelName
import com.sebialex.agent.ui.settings.sourceLine
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/** What the user picked from the "+" sheet. */
enum class AttachSource { CAMERA, PHOTOS, FILES }

/**
 * The "+" sheet: camera, photos and files tiles, then the model for this chat (one quiet row
 * that opens a clean searchable list in place) and the thinking switch for that model.
 * Per-conversation options such as memory of past chats live in the chat's options sheet.
 */
@Composable
fun AttachSheet(
    settings: SecureSettings,
    models: OpenRouterModels,
    ollama: OllamaCloud,
    store: ModelStore,
    engine: LocalEngine,
    onPick: (AttachSource) -> Unit,
    onOpenOnDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choosingModel by remember { mutableStateOf(false) }
    AppSheet(onDismiss = onDismiss, title = if (choosingModel) "Model" else null) {
        if (choosingModel) {
            ModelList(
                settings = settings,
                models = models,
                ollama = ollama,
                store = store,
                engine = engine,
                onPicked = { choosingModel = false },
                onOpenOnDevice = { onDismiss(); onOpenOnDevice() },
                maxHeight = 460.dp,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachTile(R.drawable.ic_camera, "Camera", Modifier.weight(1f)) { onPick(AttachSource.CAMERA) }
                AttachTile(R.drawable.ic_image, "Photos", Modifier.weight(1f)) { onPick(AttachSource.PHOTOS) }
                AttachTile(R.drawable.ic_file, "Files", Modifier.weight(1f)) { onPick(AttachSource.FILES) }
            }
            Spacer(Modifier.height(10.dp))
            ModelRow(settings, onClick = { choosingModel = true })
            ThinkingToggle(settings, models)
        }
    }
}

/** Current model and where it runs; tapping opens the list inside the same sheet. */
@Composable
private fun ModelRow(settings: SecureSettings, onClick: () -> Unit) {
    val snap by settings.state.collectAsStateWithLifecycle()
    SheetOption(
        icon = R.drawable.ic_code,
        title = modelName(snap.model),
        subtitle = sourceLine(snap.model, snap.localBackend),
        onClick = onClick,
    ) {
        AppIcon(R.drawable.ic_chevron_right, "Choose model", tint = Palette.IconMuted, size = 16.dp)
    }
}

@Composable
private fun AttachTile(@DrawableRes icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(92.dp)
            .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 2.dp)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(icon, null, tint = Palette.IconActive, size = 24.dp)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
    }
}

/**
 * Quick "Thinking" switch for the selected model. It writes the same per-model preference as
 * Settings, so both always agree; it is disabled when the model has no controllable reasoning.
 */
@Composable
private fun ThinkingToggle(settings: SecureSettings, models: OpenRouterModels) {
    val snap by settings.state.collectAsStateWithLifecycle()
    var info by remember(snap.model) { mutableStateOf<ModelReasoning?>(null) }
    LaunchedEffect(snap.model) {
        info = try {
            models.reasoning(snap.model)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val current = info
    val on = current != null && ReasoningRules.resolve(current, snap.reasoning).on
    val enabled = current?.canDisable == true
    // Readable name plus where it runs, e.g. "Gemma 4 E2B · On this phone · GPU".
    val name = modelName(snap.model) + " \u00b7 " + sourceLine(snap.model, snap.localBackend)
    val subtitle = when {
        current == null -> "Checking $name\u2026"
        !current.supported -> "Not available for $name"
        !current.canDisable -> "Always on for $name"
        else -> name
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { settings.setReasoningEnabled(snap.model, !on) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_lightbulb, null, tint = Palette.IconMuted, size = 20.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Thinking", style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1)
        }
        Switch(
            checked = on,
            enabled = enabled,
            onCheckedChange = { settings.setReasoningEnabled(snap.model, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.IconMuted,
                uncheckedTrackColor = Palette.SurfaceHigh,
                uncheckedBorderColor = Palette.SurfaceHighest,
            ),
        )
    }
}
