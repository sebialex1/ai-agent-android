package com.sebialex.agent.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.llm.ReasoningPreference
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.local.LocalEngine
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * Settings sections. The settings home is a vertical list of these; tapping one opens it as its
 * own page, with the section name in the top bar next to Back (no tab strip, no pill slider).
 */
enum class SettingsSection(val label: String, @androidx.annotation.DrawableRes val icon: Int) {
    GENERAL("General", R.drawable.ic_shield),
    MODELS("Models", R.drawable.ic_code),
    ON_DEVICE("On-device", R.drawable.ic_phone),
    CHATS("Chats", R.drawable.ic_history),
    ABOUT("About", R.drawable.ic_info),
}

@Composable
fun SettingsScreen(
    settings: SecureSettings,
    models: OpenRouterModels,
    termux: com.sebialex.agent.data.shell.TermuxBridge,
    modelStore: ModelStore,
    localEngine: LocalEngine,
    ollama: OllamaCloud,
    onBack: () -> Unit,
    onClearConversations: () -> Unit,
    /** Opens straight into one section (e.g. On-device from the model picker); Back then leaves Settings. */
    initialSection: SettingsSection? = null,
) {
    val snap by settings.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(initialSection) }
    // Opened directly into a section: Back leaves Settings instead of showing the list first.
    val direct = initialSection != null
    // One scroll position per page, so going back and forth never jumps.
    val homeScroll = rememberScrollState()
    val scrolls = SettingsSection.entries.associateWith { rememberScrollState() }
    var picking by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    fun back() {
        if (section != null && !(direct && section == initialSection)) section = null else onBack()
    }
    // Inside a section, system Back returns to the list first (registered after the app's own
    // handler, so it takes precedence while enabled).
    androidx.activity.compose.BackHandler(enabled = section != null && !(direct && section == initialSection)) { section = null }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIconButton(R.drawable.ic_back, "Back", onClick = ::back)
            AnimatedContent(
                targetState = section?.label ?: "Settings",
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                label = "settingsTitle",
            ) { title ->
                Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, modifier = Modifier.padding(start = 8.dp))
            }
        }
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                // Into a section slides in from the right; back to the list slides the other way.
                val forward = targetState != null
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(220)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(180)) { -it / 6 * dir } + fadeOut(tween(140)))
            },
            label = "settingsPage",
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val scroll = if (page == null) homeScroll else scrolls.getValue(page)
            Column(
                Modifier
                    .fillMaxSize()
                    // Rows scrolling under the header dissolve instead of showing a cut-off line.
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        if (scroll.value > 0) {
                            val fade = 28.dp.toPx().coerceAtMost(size.height)
                            drawRect(
                                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade),
                                size = androidx.compose.ui.geometry.Size(size.width, fade),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (page == null) 8.dp else 28.dp),
            ) {
                when (page) {
                    null -> SettingsSection.entries.forEach { s -> SectionRow(s) { section = s } }
                    SettingsSection.GENERAL -> {
                        Group("Permissions") {
                            SwitchRow(
                                title = "Ask before web access",
                                subtitle = null,
                                checked = snap.askBeforeWeb,
                                onChange = settings::setAskBeforeWeb,
                            )
                            SwitchRow(
                                title = "Auto-approve agent actions",
                                subtitle = "Installs, deletions and network commands run without asking. Blocked actions stay blocked.",
                                checked = snap.autoApprove,
                                onChange = settings::setAutoApprove,
                            )
                        }
                        SandboxSetting(termux)
                    }
                    SettingsSection.MODELS -> {
                        ModelPickerRow(snap.model, snap.localBackend, onClick = { picking = true })
                        ReasoningSetting(
                            model = snap.model,
                            pref = snap.reasoning,
                            models = models,
                            onEnabled = { settings.setReasoningEnabled(snap.model, it) },
                            onEffort = { settings.setReasoningEffort(snap.model, it) },
                        )
                        Group("API keys") {
                            KeyField(
                                label = "OpenRouter",
                                saved = snap.hasOpenRouterKey,
                                hint = snap.openRouterHint,
                                onSave = settings::setOpenRouterKey,
                                onClear = { settings.setOpenRouterKey(null) },
                            )
                            KeyField(
                                label = "Ollama Cloud",
                                saved = snap.hasOllamaKey,
                                hint = snap.ollamaHint,
                                onSave = settings::setOllamaKey,
                                onClear = { settings.setOllamaKey(null) },
                            )
                            Note("Create a key at ollama.com/settings/keys.")
                            KeyField(
                                label = "Web search (TinyFish)",
                                saved = snap.hasTinyFishKey,
                                hint = snap.tinyFishHint,
                                onSave = settings::setTinyFishKey,
                                onClear = { settings.setTinyFishKey(null) },
                            )
                            Note("Optional. Without it, the phone searches directly.")
                        }
                    }
                    SettingsSection.ON_DEVICE -> OnDeviceSettings(settings, modelStore, localEngine)
                    SettingsSection.CHATS -> {
                        // Memory of past chats has no row here: its only switch is in each chat's
                        // options (the \u22ef next to a chat in the list).
                        SwitchRow(
                            title = "Auto-generate titles",
                            subtitle = null,
                            checked = snap.autoTitles,
                            onChange = settings::setAutoTitles,
                        )
                        if (!confirmClear) {
                            Text(
                                "Delete all conversations",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Palette.Danger,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { confirmClear = true }.padding(vertical = 8.dp),
                            )
                        } else {
                            com.sebialex.agent.ui.components.SheetConfirm(
                                message = "Every conversation, its workspace files and history will be deleted. This cannot be undone.",
                                confirm = "Delete all",
                                onConfirm = { confirmClear = false; onClearConversations() },
                                onCancel = { confirmClear = false },
                            )
                        }
                    }
                    SettingsSection.ABOUT -> AboutTab()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (picking) {
        ModelPickerSheet(
            settings = settings,
            models = models,
            ollama = ollama,
            store = modelStore,
            engine = localEngine,
            onOpenOnDevice = { section = SettingsSection.ON_DEVICE },
            onDismiss = { picking = false },
        )
    }
}

/** One row of the settings home: icon, section name and a chevron; opens the section's page. */
@Composable
private fun SectionRow(section: SettingsSection, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(18.dp), Palette.Surface, elevation = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(section.icon, null, tint = Palette.IconMuted, size = 20.dp)
        Spacer(Modifier.width(16.dp))
        Text(section.label, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
        AppIcon(R.drawable.ic_chevron_right, null, tint = Palette.IconMuted, size = 16.dp)
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
}

@Composable
private fun AboutTab() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    }
    fun open(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    Group("Agent for Android") {
        Note("Version $version")
    }
    Group("Agent tools") {
        Note(com.sebialex.agent.data.llm.OpenRouterBrain.TOOL_SUMMARY)
    }
    Group("Links") {
        LinkRow("Source code", "github.com/sebialex1/ai-agent-android") { open("https://github.com/sebialex1/ai-agent-android") }
        LinkRow("Ollama Cloud docs", "docs.ollama.com/cloud") { open("https://docs.ollama.com/cloud") }
        LinkRow("Ollama API keys", "ollama.com/settings/keys") { open(OllamaCloud.KEYS_URL) }
        LinkRow("OpenRouter models", "openrouter.ai/models") { open("https://openrouter.ai/models") }
    }
}

@Composable
private fun LinkRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .softSurface(RoundedCornerShape(16.dp), Palette.SurfaceHigh, elevation = 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        AppIcon(R.drawable.ic_open_external, null, tint = Palette.IconMuted, size = 16.dp)
    }
}

private sealed interface ModelCheck {
    data object Loading : ModelCheck
    data class Ready(val info: ModelReasoning) : ModelCheck
    data object Failed : ModelCheck
}

/**
 * Reasoning toggle for the selected model, driven by its live OpenRouter metadata.
 * Unsupported models get a disabled switch; mandatory models have no off state; effort
 * choices come from the model's supported_efforts. The choice is stored per model.
 */
@Composable
private fun ReasoningSetting(
    model: String,
    pref: ReasoningPreference,
    models: OpenRouterModels,
    onEnabled: (Boolean) -> Unit,
    onEffort: (String) -> Unit,
) {
    var check by remember(model) { mutableStateOf<ModelCheck>(ModelCheck.Loading) }
    var attempt by remember(model) { mutableIntStateOf(0) }
    LaunchedEffect(model, attempt) {
        check = ModelCheck.Loading
        check = try {
            ModelCheck.Ready(models.reasoning(model, refresh = attempt > 0))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ModelCheck.Failed
        }
    }
    val info = (check as? ModelCheck.Ready)?.info
    val supported = info?.supported == true
    val resolved = info?.let { ReasoningRules.resolve(it, pref) }
    val locked = info != null && supported && !info.canDisable
    val status = when {
        check is ModelCheck.Loading -> "checking model"
        check is ModelCheck.Failed -> "could not check model, tap to retry"
        !supported -> "not supported"
        locked -> "always on for this model"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = check is ModelCheck.Failed || (supported && !locked)) {
                    if (check is ModelCheck.Failed) attempt++ else onEnabled(resolved?.on != true)
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Reasoning",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (supported) Palette.TextPrimary else Palette.TextTertiary,
                )
                if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            }
            // Mandatory reasoning has no off option, so no switch is shown at all.
            if (!locked) Switch(
                checked = resolved?.on == true,
                onCheckedChange = onEnabled,
                enabled = supported,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.OnAccent,
                    checkedTrackColor = Palette.Accent,
                    uncheckedThumbColor = Palette.IconMuted,
                    uncheckedTrackColor = Palette.SurfaceHigh,
                    uncheckedBorderColor = Palette.SurfaceHighest,
                    disabledUncheckedThumbColor = Palette.SurfaceHighest,
                    disabledUncheckedTrackColor = Palette.Surface,
                    disabledUncheckedBorderColor = Palette.SurfaceHigh,
                ),
            )
        }
        val choices = info?.effortChoices.orEmpty()
        // A single level is just "on" (Ollama boolean thinking models), so no chips for it.
        if (resolved?.on == true && choices.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                choices.forEach { effort ->
                    val selected = effort == resolved.effort
                    Text(
                        effort,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Palette.OnAccent else Palette.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) Palette.Accent else Palette.SurfaceHigh)
                            .clickable { onEffort(effort) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyField(label: String, saved: Boolean, hint: String, onSave: (String) -> Unit, onClear: () -> Unit) {
    var value by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
                .heightIn(min = 52.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_key, null, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (value.isEmpty()) Text(if (saved) hint else "Paste key", style = MonoSmall, color = Palette.TextTertiary)
                        inner()
                    },
                )
            }
            when {
                value.isNotEmpty() -> QuietIconButton(R.drawable.ic_check, "Save $label", onClick = { onSave(value); value = "" })
                saved -> QuietIconButton(R.drawable.ic_close, "Remove $label", onClick = onClear, tint = Palette.IconMuted)
            }
        }
    }
}

@Composable
internal fun PlainField(label: String, value: String, placeholder: String, onSave: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.trim() },
                    singleLine = true,
                    textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (draft.isEmpty()) Text(placeholder, style = MonoSmall, color = Palette.TextTertiary)
                        inner()
                    },
                )
            }
            if (draft != value) QuietIconButton(R.drawable.ic_check, "Save $label", onClick = { onSave(draft) })
        }
    }
}
