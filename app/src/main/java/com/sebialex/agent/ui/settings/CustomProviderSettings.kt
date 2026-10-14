package com.sebialex.agent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.launch

/**
 * Settings > Models > Custom providers: every user-added OpenAI-compatible provider as a key
 * ring card (its keys rotate like the built-in ones) with its API root and model count, plus
 * an "Add provider" button that opens the setup sheet.
 */
@Composable
internal fun CustomProvidersGroup(settings: SecureSettings, snap: SecureSettings.Snapshot) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Custom providers")
        snap.customProviders.forEach { p ->
            val ring = CustomProviders.keyRing(p.id)
            KeyRingCard(
                title = p.name,
                subtitle = if (CustomProviders.isLocalAddress(p.baseUrl)) "Local server \u00b7 a key is optional" else "Add a key to use it",
                keys = snap.keys[ring].orEmpty(),
                onAdd = { key, label -> settings.addKey(ring, key, label) },
                onRemove = { settings.removeKey(ring, it) },
                onActivate = { settings.setActiveKey(ring, it) },
                action = {
                    QuietIconButton(R.drawable.ic_trash, "Remove ${p.name}", onClick = { confirmRemove = p.id }, size = 38.dp, iconSize = 17.dp, tint = Palette.IconMuted)
                },
                footer = {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)) {
                        Text(p.baseUrl, style = MonoSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (p.models.isEmpty()) "No model list \u00b7 type a model id in the + sheet"
                            else "${p.models.size} model${if (p.models.size == 1) "" else "s"} \u00b7 pick one in the chat's + sheet",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextTertiary,
                        )
                        if (confirmRemove == p.id) {
                            com.sebialex.agent.ui.components.SheetConfirm(
                                message = "Remove ${p.name} and its keys?",
                                confirm = "Remove",
                                onConfirm = { confirmRemove = null; settings.removeCustomProvider(p.id) },
                                onCancel = { confirmRemove = null },
                            )
                        }
                    }
                },
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 2.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable { adding = true }
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_plus, null, tint = Palette.IconActive, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Add provider", style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                Text(
                    "OpenAI, Groq, Gemini, Mistral, LM Studio\u2026 or any OpenAI-compatible URL",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                )
            }
        }
    }
    if (adding) AddProviderSheet(settings, onDismiss = { adding = false })
}

/**
 * Setup sheet for a provider: a dropdown of popular providers (plus "Custom / auto-detect"),
 * name, API root and key, then Detect, which probes the URL (see [CustomProviders.detect]) and
 * shows what it found before Add. Saving without detecting first runs detection on the way.
 */
@Composable
private fun AddProviderSheet(settings: SecureSettings, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var presetId by rememberSaveable { mutableStateOf(CustomProviders.CUSTOM_PRESET) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var detecting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CustomProviders.Detection?>(null) }
    val preset = CustomProviders.preset(presetId)

    suspend fun runDetect(): CustomProviders.Detection {
        detecting = true
        val d = try { CustomProviders.detect(url, key.ifBlank { null }) } finally { detecting = false }
        result = d
        if (d.compatible) {
            url = d.baseUrl
            if (name.isBlank()) name = d.name
            if (presetId == CustomProviders.CUSTOM_PRESET && d.presetId != CustomProviders.CUSTOM_PRESET) presetId = d.presetId
        }
        return d
    }

    fun save(d: CustomProviders.Detection?) {
        val base = (d?.takeIf { it.compatible }?.baseUrl ?: CustomProviders.normalize(url)).ifEmpty { return }
        val finalName = name.trim().ifEmpty { d?.name?.ifEmpty { null } ?: preset?.name ?: "Custom provider" }
        val id = finalName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "provider" } +
            "-" + java.lang.Long.toString(System.currentTimeMillis() % 1_679_616, 36)
        settings.saveCustomProvider(
            SecureSettings.CustomProvider(
                id = id, name = finalName, baseUrl = base, preset = presetId,
                models = d?.models.orEmpty(), detected = d?.message.orEmpty(),
                modelInfo = d?.meta.orEmpty(),
            ),
        )
        if (key.isNotBlank()) settings.addKey(CustomProviders.keyRing(id), key.trim())
        onDismiss()
    }

    AppSheet(onDismiss = onDismiss, title = "Add a provider", subtitle = "Anything that speaks the OpenAI chat completions API") {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- Provider dropdown
            Row(
                Modifier
                    .fillMaxWidth()
                    .softSurface(RoundedCornerShape(16.dp), Palette.SurfaceHigh, elevation = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { choosing = !choosing }
                    .heightIn(min = 52.dp)
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(preset?.name ?: "Custom / auto-detect", style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                    Text(
                        preset?.keysUrl?.let { "Keys: $it" } ?: if (preset?.local == true) "Runs on your network" else "Paste a base URL and Detect",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextTertiary,
                    )
                }
                AppIcon(R.drawable.ic_chevron_down, if (choosing) "Close list" else "Choose provider", tint = Palette.IconMuted, size = 16.dp)
            }
            AnimatedVisibility(visible = choosing) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .softSurface(RoundedCornerShape(16.dp), Palette.Surface, elevation = 4.dp)
                        .padding(vertical = 4.dp),
                ) {
                    item(key = "custom") {
                        PresetRow("Custom / auto-detect", "Any OpenAI-compatible URL", selected = preset == null) {
                            presetId = CustomProviders.CUSTOM_PRESET; choosing = false; result = null
                        }
                    }
                    items(CustomProviders.PRESETS, key = { it.id }) { p ->
                        PresetRow(p.name, p.baseUrl.substringAfter("://"), selected = p.id == presetId) {
                            presetId = p.id
                            url = p.baseUrl
                            name = p.name
                            choosing = false
                            result = null
                        }
                    }
                }
            }
            Field("Name", name, "e.g. Groq", onChange = { name = it })
            Field("Base URL", url, "https://api.example.com/v1", onChange = { url = it.trim(); result = null }, mono = true)
            Field("API key", key, if (preset?.local == true) "Optional for local servers" else "Paste key", onChange = { key = it.trim() }, secret = true)

            // ---- Detection result
            result?.let { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (d.compatible && !d.needsKey) Palette.DiffAdd else Palette.Warning.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        if (d.compatible && !d.needsKey) R.drawable.ic_check else R.drawable.ic_warning, null,
                        tint = if (d.compatible && !d.needsKey) Palette.DiffAddText else Palette.Warning, size = 16.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        if (d.compatible) Text(d.name, style = MaterialTheme.typography.labelLarge, color = Palette.TextPrimary)
                        Text(d.message, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                        if (d.models.isNotEmpty()) Text(
                            d.models.take(4).joinToString(", ") + if (d.models.size > 4) ", \u2026" else "",
                            style = MonoSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (detecting) Text("Detecting\u2026", style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary, modifier = Modifier.weight(1f))
                PillButton("Detect", onClick = { scope.launch { runDetect() } }, enabled = url.isNotBlank() && !detecting)
                PillButton(
                    "Add provider",
                    primary = true,
                    enabled = url.isNotBlank() && !detecting,
                    onClick = {
                        val known = result
                        if (known != null) save(known) else scope.launch { save(runDetect()) }
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
            Text(detail, style = MonoSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, onChange: (String) -> Unit, mono: Boolean = false, secret: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(start = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(14.dp), Palette.SurfaceHigh, elevation = 1.dp)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            val style = if (mono || secret) MonoSmall else MaterialTheme.typography.bodyMedium
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = style.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        secret -> KeyboardType.Password
                        mono -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, style = style, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                },
            )
        }
    }
}
