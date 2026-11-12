package com.sebialex.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.local.HfHit
import com.sebialex.agent.data.local.HfSearch
import com.sebialex.agent.data.local.LocalModels
import com.sebialex.agent.data.local.ModelStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.launch

/** Settings > On-device > Hugging Face: token, search, and adding any `.litertlm` model. */
@Composable
internal fun HfGroup(settings: SecureSettings, snap: SecureSettings.Snapshot, store: ModelStore, onMessage: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<HfHit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    SettingsGroup("Hugging Face") {
        ExpandRow(
            title = if (snap.hasHfToken) "Token saved" else "Add token",
            value = if (snap.hasHfToken) "\u2022\u2022\u2022\u2022" else "",
        ) {
            TokenField(settings)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_search, null, tint = Palette.IconMuted, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search Hugging Face", style = MonoSmall, color = Palette.TextTertiary)
                    inner()
                },
            )
            PillButton(
                if (searching) "\u2026" else if (query.isBlank()) "Top" else "Search",
                onClick = {
                    searching = true
                    error = null
                    scope.launch {
                        hits = runCatching { HfSearch.search(query, settings.hfToken()) }
                            .onFailure { error = it.message ?: "Search failed" }
                            .getOrNull()
                        searching = false
                    }
                },
                enabled = !searching,
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Danger) }
        hits?.let { list ->
            if (list.isEmpty()) Text("No .litertlm models found", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            list.forEach { hit ->
                val added = snap.hfModels.any { it.repo == hit.repo && it.file == hit.file }
                HfRow(hit, added, onAdd = {
                    settings.addHfModel(hit.model)
                    onMessage(null)
                })
            }
        }
    }
}

@Composable
private fun TokenField(settings: SecureSettings) {
    val snap by settings.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(18.dp), Palette.SurfaceHigh, elevation = 3.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.trim() },
                singleLine = true,
                textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty() && !snap.hasHfToken) Text("hf_...", style = MonoSmall, color = Palette.TextTertiary)
                    inner()
                },
            )
            if (draft.isNotEmpty()) {
                PillButton("Save", onClick = {
                    settings.setHfToken(draft)
                    draft = ""
                })
            }
            if (snap.hasHfToken) PillButton("Remove", onClick = { settings.setHfToken(null) })
        }
    }
}

@Composable
private fun HfRow(hit: HfHit, added: Boolean, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(hit.file.removeSuffix(".litertlm"), style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, maxLines = 1)
            Text(
                listOfNotNull(
                    hit.repo,
                    if (hit.sizeBytes > 0) LocalModels.formatSize(hit.sizeBytes) else null,
                    if (hit.downloads > 0) LocalModels.formatSize(hit.downloads) + " downloads" else null,
                    if (hit.gated) "Gated" else null,
                ).joinToString(" \u00b7 "),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                maxLines = 1,
            )
        }
        when {
            added -> AppIcon(R.drawable.ic_check, "Added", tint = Palette.IconActive, size = 16.dp)
            else -> PillButton("Add", onClick = onAdd, primary = true)
        }
    }
}
