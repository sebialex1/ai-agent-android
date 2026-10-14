package com.sebialex.agent.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * One provider's API keys as a small stacked card: a monogram tile, the provider's name, its
 * key count and a note on rotation; then one row per key (masked, optional label) with an
 * "Active" badge on the key in use, a "Failing" badge on one that just failed, "Use" to switch
 * by hand and a remove button; then "Add key", which opens an inline field (key and an
 * optional label). With no key yet the field is shown straight away.
 *
 * The app rotates by itself: when a request fails because of the active key (invalid, out of
 * credit, rate limited), the next key becomes active and the request is retried.
 */
@Composable
internal fun KeyRingCard(
    title: String,
    keys: List<SecureSettings.KeyView>,
    onAdd: (key: String, label: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onActivate: (id: String) -> Unit,
    subtitle: String? = null,
    /** Extra content under the keys (e.g. a custom provider's URL and models). */
    footer: (@Composable () -> Unit)? = null,
    /** Extra header action (e.g. a custom provider's menu). */
    action: (@Composable () -> Unit)? = null,
) {
    var adding by rememberSaveable(title) { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .softSurface(shape, Palette.Surface, elevation = 0.dp, highlight = false)
            .clip(shape)
            .padding(vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = when {
                    keys.isEmpty() -> subtitle ?: "No key yet"
                    keys.size == 1 -> subtitle ?: "1 key"
                    else -> "${keys.size} keys \u00b7 rotates on rate limits and errors"
                }
                Text(detail, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            action?.invoke()
        }
        keys.forEachIndexed { i, k ->
            KeyRow(
                index = i + 1,
                key = k,
                showUse = keys.size > 1 && !k.active,
                onActivate = { onActivate(k.id) },
                onRemove = { onRemove(k.id) },
            )
        }
        AnimatedVisibility(
            visible = adding || keys.isEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            AddKeyField(
                onSave = { key, label -> onAdd(key, label); adding = false },
                onCancel = if (keys.isEmpty()) null else ({ adding = false }),
            )
        }
        if (!adding && keys.isNotEmpty()) {
            Row(
                Modifier
                    .padding(start = 10.dp, top = 2.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { adding = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(R.drawable.ic_plus, null, tint = Palette.IconMuted, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text("Add key", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
            }
        }
        footer?.invoke()
    }
}

@Composable
private fun KeyRow(index: Int, key: SecureSettings.KeyView, showUse: Boolean, onActivate: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(key.hint, style = MonoSmall, color = Palette.TextPrimary, maxLines = 1)
            if (key.label.isNotBlank()) Text(key.label, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            key.failing -> Badge("Failing", Color.Transparent, Palette.Warning)
            key.active -> Badge("Active", Color.Transparent, Palette.TextSecondary)
        }
        if (showUse) {
            Text(
                "Use",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextSecondary,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onActivate)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        QuietIconButton(R.drawable.ic_close, "Remove key ${key.hint}", onClick = onRemove, size = 38.dp, iconSize = 15.dp, tint = Palette.IconMuted)
    }
}

@Composable
private fun Badge(text: String, background: Color, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Inline add-key form: the key (hidden), an optional label, save and (when there are keys) cancel. */
@Composable
private fun AddKeyField(onSave: (String, String) -> Unit, onCancel: (() -> Unit)?) {
    var key by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    fun save() {
        if (key.isNotBlank()) {
            onSave(key.trim(), label.trim())
            key = ""
            label = ""
        }
    }
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(16.dp), Palette.SurfaceHigh, elevation = 2.dp)
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_key, null, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                BasicTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    singleLine = true,
                    textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (key.isEmpty()) Text("Paste API key", style = MonoSmall, color = Palette.TextTertiary)
                        inner()
                    },
                )
            }
            if (key.isNotEmpty()) QuietIconButton(R.drawable.ic_check, "Save key", onClick = { save() }, size = 40.dp, iconSize = 18.dp)
            else if (onCancel != null) QuietIconButton(R.drawable.ic_close, "Cancel", onClick = onCancel, size = 40.dp, iconSize = 16.dp, tint = Palette.IconMuted)
        }
        if (key.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .softSurface(RoundedCornerShape(14.dp), Palette.SurfaceHigh, elevation = 1.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.TextPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (label.isEmpty()) Text("Label (optional), e.g. Work, Free tier", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
                        inner()
                    },
                )
            }
        }
    }
}
