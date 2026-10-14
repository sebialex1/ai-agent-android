package com.sebialex.agent.ui.settings

import com.sebialex.agent.ui.components.keepScrollInside
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
import com.sebialex.agent.data.llm.CustomProviders
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
    val custom = snap.customProviders.associate { p -> p.id to MediaModels.customImageModels(p, snap.imageOverrides) }
    val markable = snap.customProviders.associate { p -> p.id to MediaModels.markableModels(p) }
    // Is the saved choice usable right now? Shown under the row instead of switching providers.
    val problem = runCatching { RoutedImageGenerator.resolve(current, snap.customProviders, snap.imageOverrides) { snap.keys[it].orEmpty().size } }
        .exceptionOrNull()?.message
    val providerName = MediaModels.customProviderId(current)?.let { id -> snap.customProviders.firstOrNull { it.id == id }?.name ?: "Removed provider" } ?: "OpenRouter"
    val currentName = (openRouter.orEmpty() + custom.values.flatten()).firstOrNull { it.ref == current }?.name ?: current.model

    SettingsGroup("Image generation", help = "The model the agent's generate_image tool uses. Only models that can make images are listed.") {
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
                    providerName + (if (snap.mediaImage == null) " \u00b7 default" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            AppIcon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, if (open) "Close list" else "Choose image model", tint = Palette.IconMuted, size = 16.dp)
        }
        if (problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = Palette.Danger)
        if (open) {
            // Custom providers with image models, or with models that may be marked as image models by hand.
            val tabs = listOf(MediaModels.PROVIDER_OPENROUTER) +
                snap.customProviders.filter { custom[it.id].orEmpty().isNotEmpty() || markable[it.id].orEmpty().isNotEmpty() }.map { it.id }
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
                items.isEmpty() -> Text(if (markable[tab].isNullOrEmpty()) "No image models listed." else "No image models listed. Mark one by hand below.", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).keepScrollInside()) {
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
            snap.customProviders.firstOrNull { it.id == tab }?.let { p ->
                val marks = markable[p.id].orEmpty()
                if (marks.isNotEmpty()) ManualImageMarks(p, marks, snap.imageOverrides, onToggle = { id, on -> settings.setImageOverride(id, on) })
            }
            // Honest limits: providers that are connected but cannot generate images here.
            val limits = snap.customProviders.filter { custom[it.id].isNullOrEmpty() && markable[it.id].isNullOrEmpty() }
                .map { "${it.name}: its model list names no image models this app can call" } +
                MediaModels.LIMITATIONS.map { (name, why) -> "$name: $why" }
            if (limits.isNotEmpty()) Help(limits.joinToString("\n"), label = "Providers without image models")
        }
    }
}

/**
 * The manual "this is an image model" override for one custom provider: a single collapsed row
 * that opens the models its list says nothing about, each with a check mark. Marked models join
 * the picker above, labelled as manual.
 */
@Composable
private fun ManualImageMarks(
    provider: SecureSettings.CustomProvider,
    models: List<String>,
    overrides: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    var open by remember(provider.id) { mutableStateOf(false) }
    var showAll by remember(provider.id) { mutableStateOf(false) }
    val marked = models.count { CustomProviders.modelId(provider.id, it) in overrides }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mark image models by hand", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            Text(
                "Manual override" + if (marked > 0) " \u00b7 $marked marked" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
            )
        }
        AppIcon(if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, if (open) "Close" else "Mark image models", tint = Palette.IconMuted, size = 16.dp)
    }
    if (!open) return
    // Marked models first, so a mark is easy to undo.
    val rows = models.sortedBy { if (CustomProviders.modelId(provider.id, it) in overrides) 0 else 1 }
    val visible = if (showAll) rows else rows.take(12)
    Column {
        visible.forEach { m ->
            val id = CustomProviders.modelId(provider.id, m)
            val on = id in overrides
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onToggle(id, !on) }.padding(horizontal = 4.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(if (on) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox, if (on) "Marked as image model" else "Not marked", tint = if (on) Palette.IconActive else Palette.IconMuted, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(m, style = MaterialTheme.typography.bodyMedium, color = if (on) Palette.TextPrimary else Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (rows.size > visible.size) Text(
            "Show all ${rows.size} models",
            style = MaterialTheme.typography.labelLarge,
            color = Palette.TextSecondary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showAll = true }.padding(vertical = 8.dp),
        )
        Help("A marked model is called at ${provider.baseUrl.trimEnd('/')}/images/generations. If it is not an image model there, generate_image fails and says so.")
    }
}

