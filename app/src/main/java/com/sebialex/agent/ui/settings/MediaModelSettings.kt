package com.sebialex.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.llm.MediaModelOption
import com.sebialex.agent.data.llm.MediaModels
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.llm.RoutedImageGenerator
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette

/**
 * Settings > Models > Image generation: which provider and model generate_image uses. One row
 * shows the current choice; tapping it opens a provider segmented control and that provider's
 * image models only (OpenRouter's Image API catalog, custom providers' image models). Providers
 * that cannot generate images are named with the reason instead of being offered.
 */
@Composable
internal fun ImageModelGroup(settings: SecureSettings, snap: SecureSettings.Snapshot, models: OpenRouterModels) {
    val current = snap.mediaImage ?: MediaModels.DEFAULT
    var open by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var openRouter by remember { mutableStateOf<List<MediaModelOption>?>(null) }
    var openRouterError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open, attempt) {
        if (!open) return@LaunchedEffect
        openRouterError = null
        try {
            openRouter = models.imageModels(refresh = attempt > 0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            openRouterError = e.message ?: "could not read OpenRouter's image models"
        }
    }
    val custom = snap.customProviders.associate { p -> p.id to MediaModels.customImageModels(p) }
    // Is the saved choice usable right now? Shown under the row instead of switching providers.
    val problem = runCatching { RoutedImageGenerator.resolve(current, snap.customProviders) { snap.keys[it].orEmpty().size } }
        .exceptionOrNull()?.message
    val providerName = MediaModels.customProviderId(current)?.let { id -> snap.customProviders.firstOrNull { it.id == id }?.name ?: "Removed provider" } ?: "OpenRouter"
    val currentName = (openRouter.orEmpty() + custom.values.flatten()).firstOrNull { it.ref == current }?.name ?: current.model

    SettingsGroup("Image generation") {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(currentName, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    providerName + (if (snap.mediaImage == null) " \u00b7 default" else "") + " \u00b7 used by generate_image",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            AppIcon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, if (open) "Close list" else "Choose image model", tint = Palette.IconMuted, size = 16.dp)
        }
        if (problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = Palette.Danger)
        if (open) {
            val tabs = listOf(MediaModels.PROVIDER_OPENROUTER) + snap.customProviders.filter { custom[it.id].orEmpty().isNotEmpty() }.map { it.id }
            var tab by remember(current.provider) {
                mutableStateOf(MediaModels.customProviderId(current)?.takeIf { it in tabs } ?: MediaModels.PROVIDER_OPENROUTER)
            }
            if (tabs.size > 1) ChoiceChips(
                options = tabs,
                selected = tab,
                label = { id -> if (id == MediaModels.PROVIDER_OPENROUTER) "OpenRouter" else snap.customProviders.firstOrNull { it.id == id }?.name ?: id },
                onSelect = { tab = it },
            )
            val items: List<MediaModelOption>? = if (tab == MediaModels.PROVIDER_OPENROUTER) openRouter else custom[tab]
            when {
                tab == MediaModels.PROVIDER_OPENROUTER && openRouterError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(openRouterError.orEmpty(), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.weight(1f))
                    QuietIconButton(R.drawable.ic_refresh, "Try again", onClick = { attempt++ }, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
                }
                items == null -> Text("Loading image models\u2026", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                items.isEmpty() -> Text("No image models listed.", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(items, key = { it.ref.provider + "|" + it.ref.model }) { o ->
                        PickRow(
                            title = o.name,
                            subtitle = if (o.name != o.ref.model) o.ref.model else o.source,
                            selected = o.ref == current,
                            onClick = { settings.setMediaImageModel(o.ref); open = false },
                        )
                    }
                }
            }
            // Honest limits: providers that are connected but cannot generate images here.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                snap.customProviders.filter { custom[it.id].isNullOrEmpty() }.forEach { p ->
                    LimitNote("${p.name}: its model list names no image models this app can call")
                }
                MediaModels.LIMITATIONS.forEach { (name, why) -> LimitNote("$name: $why") }
            }
        }
    }
}

@Composable
private fun LimitNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
}
