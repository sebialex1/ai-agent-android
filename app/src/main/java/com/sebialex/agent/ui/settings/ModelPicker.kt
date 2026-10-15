package com.sebialex.agent.ui.settings

import com.sebialex.agent.ui.components.keepScrollInside
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.llm.CatalogModel
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.llm.OllamaModel
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.local.ModelStatus
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/** Where a model runs. Cloud providers and this phone are kept strictly apart in the picker. */
internal enum class ModelSource(val label: String) {
    OPENROUTER("OpenRouter"),
    OLLAMA("Ollama Cloud"),
    CUSTOM("Custom provider"),
    DEVICE("On this phone");

    companion object {
        fun of(model: String): ModelSource = when {
            LocalModels.isLocal(model) -> DEVICE
            OllamaCloud.isOllama(model) -> OLLAMA
            com.sebialex.agent.data.llm.CustomProviders.isCustom(model) -> CUSTOM
            else -> OPENROUTER
        }
    }
}

/** One-line description of where [model] runs, shown under its name. */
internal fun sourceLine(
    model: String,
    backend: String,
    providers: List<SecureSettings.CustomProvider> = emptyList(),
): String = when (ModelSource.of(model)) {
    ModelSource.DEVICE -> "On this phone \u00b7 ${if (backend == SecureSettings.BACKEND_CPU) "CPU" else "GPU"}"
    ModelSource.OLLAMA -> "Ollama Cloud"
    ModelSource.OPENROUTER -> "OpenRouter"
    ModelSource.CUSTOM -> com.sebialex.agent.data.llm.CustomProviders.parse(model)?.first
        ?.let { id -> providers.firstOrNull { it.id == id }?.name } ?: "Custom provider"
}

/** Human name for any model id: catalog name, Ollama name without prefix, or the OpenRouter id. */
internal fun modelName(model: String): String = when {
    OllamaCloud.isOllama(model) -> OllamaCloud.wireName(model)
    com.sebialex.agent.data.llm.CustomProviders.isCustom(model) -> com.sebialex.agent.data.llm.CustomProviders.parse(model)?.second ?: model
    else -> LocalModels.displayName(model)
}

/** The "Model" row in Settings: current model and where it runs; opens the picker. */
@Composable
internal fun ModelPickerRow(model: String, backend: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Model")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
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

private sealed interface Listing<out T> {
    data object Loading : Listing<Nothing>
    data class Ready<T>(val items: List<T>) : Listing<T>
    data class Failed(val message: String) : Listing<Nothing>
}

/** True when the provider returned no usable list, so typing an id is the only way in. */
private fun Listing<*>.needsManualInput(): Boolean = this is Listing.Failed || (this is Listing.Ready<*> && items.isEmpty())

/**
 * The model picker as a standalone sheet (Settings > Models). The "+" sheet in the chat embeds
 * the same [ModelList].
 */
@Composable
fun ModelPickerSheet(
    settings: SecureSettings,
    models: OpenRouterModels,
    ollama: OllamaCloud,
    store: ModelStore,
    engine: LocalEngine,
    onOpenOnDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismiss = onDismiss, title = "Model") {
        ModelList(settings, models, ollama, store, engine, onPicked = onDismiss, onOpenOnDevice = { onDismiss(); onOpenOnDevice() })
    }
}

/**
 * One clean, searchable list of every model that can answer right now: downloaded on-device
 * models, then Ollama Cloud and OpenRouter, each pulled live from the provider (`/api/tags`,
 * `/api/v1/models`). Providers are quiet section labels, not pill buttons. A manual model-id
 * field only appears for a provider whose live list failed or came back empty.
 */
@Composable
fun ModelList(
    settings: SecureSettings,
    models: OpenRouterModels,
    ollama: OllamaCloud,
    store: ModelStore,
    engine: LocalEngine,
    onPicked: () -> Unit,
    onOpenOnDevice: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp = 520.dp,
    /**
     * Multi-select mode (Settings > Models > Subagents): rows show [chosen] as selected and a tap
     * calls [onToggle] instead of switching the chat model. On-device models are left out there.
     */
    chosen: Set<String>? = null,
    onToggle: ((String) -> Unit)? = null,
) {
    val snap by settings.state.collectAsStateWithLifecycle()
    val statuses by store.statuses.collectAsStateWithLifecycle()
    val imported by store.imported.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var router by remember { mutableStateOf<Listing<CatalogModel>>(Listing.Loading) }
    var hosted by remember { mutableStateOf<Listing<OllamaModel>>(Listing.Loading) }

    LaunchedEffect(attempt, snap.hasOpenRouterKey) {
        if (!snap.hasOpenRouterKey) return@LaunchedEffect
        router = Listing.Loading
        router = try {
            Listing.Ready(models.list(refresh = attempt > 0))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Listing.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    LaunchedEffect(attempt, snap.ollamaHint) {
        if (!snap.hasOllamaKey) return@LaunchedEffect
        hosted = Listing.Loading
        hosted = try {
            Listing.Ready(ollama.models(refresh = attempt > 0))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Listing.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    val multi = onToggle != null
    fun isSelected(id: String) = if (multi) id in chosen.orEmpty() else snap.model == id
    fun pickCloud(id: String) {
        if (onToggle != null) { onToggle(id); return }
        settings.setModel(id)
        engine.unloadAsync()
        onPicked()
    }
    val q = query.trim()
    fun matches(vararg texts: String) = q.isEmpty() || texts.any { it.contains(q, ignoreCase = true) }

    val device = (LocalModels.catalog + imported).filter { statuses[it.id] is ModelStatus.Ready && matches(it.name, it.id) }
    val routerItems = (router as? Listing.Ready)?.items.orEmpty()
    val hostedItems = (hosted as? Listing.Ready)?.items.orEmpty()
    // Custom providers list the models saved at setup (refreshed from /models when opened).
    val customs = snap.customProviders
    var customLists by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(attempt, customs.map { it.id to it.baseUrl }) {
        customs.forEach { p ->
            val key = settings.activeKey(com.sebialex.agent.data.llm.CustomProviders.keyRing(p.id))
            val catalog = runCatching { com.sebialex.agent.data.llm.CustomProviders.fetchCatalog(p.baseUrl, key) }.getOrNull()
            val fresh = catalog?.first
            if (!fresh.isNullOrEmpty()) {
                customLists = customLists + (p.id to fresh)
                // Capability metadata (image output, reasoning) is refreshed with the list.
                val meta = catalog.second
                if (fresh != p.models || meta != p.modelInfo) settings.saveCustomProvider(p.copy(models = fresh, modelInfo = meta))
            }
        }
    }
    val customItems = customs.associate { p -> p.id to (customLists[p.id] ?: p.models).filter { matches(it, p.name) } }
    val total = device.size + routerItems.size + hostedItems.size + customItems.values.sumOf { it.size }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (total > 8) SearchField(query, { query = it })
        // A fixed height: a list that grows while models load (or shrinks while searching) makes
        // the sheet re-anchor with an animation, and a drag that starts during that animation is
        // taken by the sheet instead of the list. With leftover scroll kept inside the list, the
        // sheet stays put while the list is dragged.
        val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        LazyColumn(Modifier.fillMaxWidth().height(minOf(maxHeight, screen * 0.62f)).keepScrollInside()) {
            // ---- On this phone: only models that are ready to run.
            if (!multi) item(key = "h:device") { Header("On this phone", action = "Manage", onAction = onOpenOnDevice) }
            if (!multi && device.isEmpty() && q.isEmpty()) item(key = "device:none") {
                Note("No downloaded models. Get Gemma 4 and others under Manage.")
            }
            items(if (multi) emptyList() else device, key = { "d:" + it.id }) { m ->
                PickRow(
                    title = m.name,
                    subtitle = listOfNotNull(
                        (statuses[m.id] as? ModelStatus.Ready)?.bytes?.takeIf { it > 0 }?.let { LocalModels.formatSize(it) },
                        if (snap.localBackend == SecureSettings.BACKEND_CPU) "CPU" else "GPU",
                        if (m.thinking) "thinking" else null,
                    ).joinToString(" \u00b7 "),
                    selected = isSelected(m.id),
                    onClick = { settings.setModel(m.id); engine.preload(m); onPicked() },
                )
            }

            // ---- Ollama Cloud
            if (snap.hasOllamaKey) {
                item(key = "h:ollama") { Header("Ollama Cloud") }
                val custom = snap.ollamaCustomModel.takeIf { it.isNotBlank() }?.let { OllamaCloud.PREFIX + it }
                val extra = listOfNotNull(custom, snap.ollamaModel.takeIf { it.isNotEmpty() })
                    .distinct().filter { id -> hostedItems.none { it.id == id } && matches(id) }
                items(extra, key = { "ox:$it" }) { id ->
                    PickRow(OllamaCloud.wireName(id), if (id == custom) "Custom model ID" else "Last used", isSelected(id), onClick = { pickCloud(id) })
                }
                when (val l = hosted) {
                    Listing.Loading -> item(key = "o:loading") { Note("Loading models\u2026") }
                    is Listing.Failed -> item(key = "o:failed") { Retry("Could not load the list: ${l.message}") { attempt++ } }
                    is Listing.Ready -> items(l.items.filter { matches(it.name) }, key = { "o:" + it.id }) { m ->
                        PickRow(
                            title = m.name,
                            subtitle = if (OllamaCloud.knownThinking(m.name) || OllamaCloud.alwaysThinking(m.name)) "thinking" else null,
                            selected = isSelected(m.id),
                            onClick = { pickCloud(m.id) },
                        )
                    }
                }
                if (hosted.needsManualInput()) item(key = "o:manual") {
                    ModelIdField(
                        placeholder = "Model name, e.g. gemma4:31b",
                        initial = snap.ollamaCustomModel,
                        onSubmit = { raw ->
                            val name = OllamaCloud.normalizeName(raw)
                            settings.setOllamaCustomModel(name)
                            if (name.isNotEmpty()) pickCloud(OllamaCloud.PREFIX + name)
                        },
                    )
                }
            }

            // ---- OpenRouter
            if (snap.hasOpenRouterKey) {
                item(key = "h:openrouter") { Header("OpenRouter") }
                val pinnedIds = listOf(SecureSettings.DEFAULT_MODEL, snap.remoteModel).distinct()
                    .filter { id -> routerItems.none { it.id == id } && matches(id, "auto") }
                items(pinnedIds, key = { "rx:$it" }) { id ->
                    PickRow(
                        title = if (id == SecureSettings.DEFAULT_MODEL) "Auto" else id,
                        subtitle = if (id == SecureSettings.DEFAULT_MODEL) "Picks a suitable model per request" else "Last used",
                        selected = isSelected(id),
                        onClick = { pickCloud(id) },
                    )
                }
                when (val l = router) {
                    Listing.Loading -> item(key = "r:loading") { Note("Loading models\u2026") }
                    is Listing.Failed -> item(key = "r:failed") { Retry("Could not load the list: ${l.message}") { attempt++ } }
                    is Listing.Ready -> items(l.items.filter { matches(it.name, it.id, it.vendor) }, key = { "r:" + it.id }) { m ->
                        PickRow(
                            title = m.name,
                            subtitle = listOfNotNull(
                                m.vendor.takeIf { it.isNotBlank() },
                                m.contextLength?.let { formatContext(it) },
                                if (m.thinking) "thinking" else null,
                                if (m.free) "free" else null,
                            ).joinToString(" \u00b7 "),
                            selected = isSelected(m.id),
                            onClick = { pickCloud(m.id) },
                        )
                    }
                }
                if (router.needsManualInput()) item(key = "r:manual") {
                    ModelIdField(
                        placeholder = "Model id, e.g. anthropic/claude-sonnet-4.5",
                        onSubmit = { id -> if (id.isNotBlank() && !LocalModels.isLocal(id) && !OllamaCloud.isOllama(id)) pickCloud(id) },
                    )
                }
            }

            // ---- Custom OpenAI-compatible providers (Settings > Models > Custom providers)
            customs.forEach { p ->
                item(key = "h:c:" + p.id) { Header(p.name) }
                val ids = customItems[p.id].orEmpty()
                items(ids, key = { "c:" + p.id + ":" + it }) { m ->
                    val id = com.sebialex.agent.data.llm.CustomProviders.modelId(p.id, m)
                    PickRow(title = m, subtitle = null, selected = isSelected(id), onClick = { pickCloud(id) })
                }
                val current = com.sebialex.agent.data.llm.CustomProviders.parse(snap.model)?.takeIf { it.first == p.id }?.second
                if (!multi && current != null && current !in ids && matches(current)) item(key = "c:" + p.id + ":current") {
                    PickRow(title = current, subtitle = "Typed model id", selected = true, onClick = { })
                }
                if (ids.isEmpty() && q.isEmpty()) item(key = "c:" + p.id + ":manual") {
                    ModelIdField(
                        placeholder = "Model id, e.g. llama-3.3-70b",
                        initial = current.orEmpty(),
                        onSubmit = { m -> if (m.isNotBlank()) pickCloud(com.sebialex.agent.data.llm.CustomProviders.modelId(p.id, m.trim())) },
                    )
                }
            }

            if (!snap.hasOpenRouterKey && !snap.hasOllamaKey && customs.isEmpty()) item(key = "nokeys") {
                Note("Add an OpenRouter or Ollama Cloud API key in Settings \u203a Models to use cloud models.")
            }
            if (q.isNotEmpty() && total == 0) item(key = "nomatch") { Note("No models match \u201c$q\u201d") }
        }
    }
}

private fun formatContext(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M ctx"
    tokens >= 1_000 -> "${tokens / 1_000}K ctx"
    else -> "$tokens ctx"
}

@Composable
private fun Header(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary, modifier = Modifier.weight(1f))
        if (action != null) Text(
            action,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun Retry(text: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.weight(1f).padding(vertical = 8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        QuietIconButton(R.drawable.ic_refresh, "Try again", onClick = onRetry, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_search, null, tint = Palette.IconMuted, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.TextPrimary),
            cursorBrush = SolidColor(Palette.TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("Search models", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
                inner()
            },
        )
        if (value.isNotEmpty()) QuietIconButton(R.drawable.ic_close, "Clear", onClick = { onChange("") }, tint = Palette.IconMuted, size = 36.dp, iconSize = 16.dp)
    }
}

@Composable
internal fun PickRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (!enabled) Palette.TextTertiary else if (selected) Palette.TextPrimary else Palette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            AppIcon(R.drawable.ic_check, "Selected", tint = Palette.IconActive, size = 18.dp)
        }
    }
}

@Composable
private fun ModelIdField(
    placeholder: String,
    onSubmit: (String) -> Unit,
    initial: String = "",
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            "No models were listed. Enter one by hand:",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextTertiary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Surface)
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.trim() },
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
            if ((draft.isNotEmpty() || initial.isNotEmpty()) && draft != initial) {
                QuietIconButton(R.drawable.ic_check, "Use $draft", onClick = { onSubmit(draft) })
            }
        }
    }
}
