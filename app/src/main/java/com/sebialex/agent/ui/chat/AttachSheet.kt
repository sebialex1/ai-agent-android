package com.sebialex.agent.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.SheetOption
import com.sebialex.agent.ui.settings.ModelList
import com.sebialex.agent.ui.settings.ThinkingControl
import com.sebialex.agent.ui.settings.modelName
import com.sebialex.agent.ui.settings.sourceLine
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/** What the user picked from the "+" sheet. */
enum class AttachSource { CAMERA, PHOTOS, FILES }

/**
 * The "+" sheet: camera, photos and files tiles, then the model (one quiet row that opens a
 * clean searchable list in place) and the thinking switch and effort for that model. This is
 * the only place to pick the active model and its reasoning; Settings > Models holds the keys.
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
    val snap by settings.state.collectAsStateWithLifecycle()
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
            ThinkingControl(settings, snap, models)
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
        subtitle = sourceLine(snap.model, snap.localBackend, snap.customProviders),
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
 * Thinking control for the selected model, built from what its provider supports: a plain switch
 * for on/off models, one segmented control for models with Auto and/or effort levels, a note for
 * always-on, unsupported and unknown models. It writes the same per-model preference as before
 * (reasoning_enabled:: / reasoning_effort::), so backups and older settings keep working.
 */
