package com.sebialex.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.persistence.UserMemoryStore
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.model.UserMemory
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Memory: what the agent learned about the user (the user_memory tool). The agent
 * saves and updates these while chatting; here the user reads, edits and deletes every entry.
 */
@Composable
internal fun MemorySettings(settings: SecureSettings, store: UserMemoryStore) {
    val snap = settings.state.value
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<UserMemory>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<UserMemory?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    LaunchedEffect(revision) { memories = withContext(Dispatchers.IO) { store.all() } }

    SettingsGroup("Learn about you") {
        SwitchRow(
            title = "Remember what I like",
            subtitle = null,
            checked = snap.aboutUser,
            onChange = settings::setAboutUser,
        )
        if (memories.isEmpty()) {
            RowDivider()
            Text(
                if (snap.aboutUser) "Nothing saved yet. The agent saves lasting facts here while you chat."
                else "The agent is not saving anything about you.",
                style = MonoSmall,
                color = Palette.TextTertiary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        } else {
            memories.forEach { m ->
                RowDivider()
                MemoryRow(
                    m,
                    onEdit = { editing = m },
                    onDelete = { scope.launch(Dispatchers.IO) { store.forget(m.id) }; revision++ },
                )
            }
            RowDivider()
            if (!confirmForget) {
                NavRow("Forget all memories", icon = R.drawable.ic_trash, onClick = { confirmForget = true }, chevron = false)
            } else {
                com.sebialex.agent.ui.components.SheetConfirm(
                    message = "Delete every saved memory about you? The agent starts learning fresh.",
                    confirm = "Forget all",
                    onConfirm = {
                        confirmForget = false
                        scope.launch(Dispatchers.IO) { store.clear() }
                        revision++
                    },
                    onCancel = { confirmForget = false },
                )
            }
        }
    }
    editing?.let { current ->
        EditMemoryDialog(
            memory = current,
            onSave = { text, topic ->
                editing = null
                scope.launch(Dispatchers.IO) { store.update(current.id, text, topic) }
                revision++
            },
            onDismiss = { editing = null },
        )
    }
}

/** One memory: its optional topic tag, the fact itself (tap to edit), delete on the right. */
@Composable
private fun MemoryRow(m: UserMemory, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (m.topic.isNotBlank()) {
                Text(
                    m.topic,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                m.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppIcon(
            R.drawable.ic_trash, "Delete memory", tint = Palette.IconMuted, size = 18.dp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDelete)
                .padding(6.dp),
        )
    }
}

/** Edit one memory: the fact and its optional topic. Deleting happens in the list. */
@Composable
private fun EditMemoryDialog(
    memory: UserMemory,
    onSave: (text: String, topic: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(memory.text) }
    var topic by remember { mutableStateOf(memory.topic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("Edit memory", color = Palette.TextPrimary, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    value = topic,
                    onValueChange = { topic = it.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.TextPrimary, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(Palette.Accent),
                    decorationBox = { inner ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp)) {
                            if (topic.isEmpty()) Text("Topic (optional)", style = MonoSmall, color = Palette.TextTertiary)
                            inner()
                        }
                    },
                )
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(400) },
                    maxLines = 5,
                    textStyle = TextStyle(color = Palette.TextPrimary, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(Palette.Accent),
                    decorationBox = { inner ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp)) {
                            if (text.isEmpty()) Text("What to remember", style = MonoSmall, color = Palette.TextTertiary)
                            inner()
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim(), topic.trim()) }, enabled = text.isNotBlank()) {
                Text("Save", color = if (text.isNotBlank()) Palette.TextPrimary else Palette.TextTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.TextSecondary) }
        },
    )
}
