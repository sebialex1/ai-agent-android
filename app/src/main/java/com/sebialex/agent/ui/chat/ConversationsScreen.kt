package com.sebialex.agent.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.R
import com.sebialex.agent.domain.model.SessionSummary
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.FadingText
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/**
 * Minimal conversations menu, reached from the top-left X in the chat.
 * Settings and search across the top, recently updated conversations, and an input at the
 * bottom that starts a brand-new conversation.
 */
@Composable
fun ConversationsScreen(
    vm: ChatViewModel,
    onOpen: (id: String) -> Unit,
    onNew: () -> Unit,
    onStartWith: (text: String) -> Unit,
    onSettings: () -> Unit,
    settings: com.sebialex.agent.data.secure.SecureSettings,
) {
    val state by vm.conversations.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf<SessionSummary?>(null) }
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            val next = (input.text + " " + it).trim()
            input = TextFieldValue(next, TextRange(next.length))
        }
    }
    val filtered = remember(state.items, query) {
        val q = query.trim()
        if (q.isEmpty()) state.items else state.items.filter { it.title.contains(q, ignoreCase = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleButton(R.drawable.ic_settings, "Settings", onSettings)
            Spacer(Modifier.width(10.dp))
            SearchPill(query, { query = it }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            CircleButton(R.drawable.ic_new_chat, "New conversation", onNew)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (filtered.isEmpty()) {
                Text(
                    if (query.isBlank()) "No conversations yet" else "No matches",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    val pinned = filtered.filter { it.pinned }
                    val recent = filtered.filterNot { it.pinned }
                    if (pinned.isNotEmpty()) {
                        item(key = "h:pinned") { ListHeader("Pinned") }
                        items(pinned, key = { "p:" + it.id }) { item ->
                            ConversationRow(
                                item = item,
                                running = item.id in state.runningIds,
                                current = item.id == state.currentId,
                                onClick = { onOpen(item.id) },
                                onOptions = { options = item },
                            )
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "h:recent") { ListHeader("Recently updated") }
                        items(recent, key = { it.id }) { item ->
                            ConversationRow(
                                item = item,
                                running = item.id in state.runningIds,
                                current = item.id == state.currentId,
                                onClick = { onOpen(item.id) },
                                onOptions = { options = item },
                            )
                        }
                    }
                }
            }
        }

        InputPill(
            value = input,
            onValueChange = { input = it },
            running = false,
            onSend = {
                val text = input.text.trim()
                if (text.isNotEmpty()) {
                    input = TextFieldValue("")
                    onStartWith(text)
                }
            },
            onStop = {},
            onAttach = null,
            onMic = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                try {
                    speech.launch(intent)
                } catch (_: ActivityNotFoundException) {
                }
            },
            placeholder = "Start a new conversation",
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp),
        )
    }

    options?.let { item ->
        ChatOptionsSheet(
            sessionId = item.id,
            title = item.title,
            pinned = item.pinned,
            settings = settings,
            onPin = { vm.setPinned(item.id, it) },
            onRename = { vm.rename(item.id, it) },
            onDelete = { vm.deleteConversation(item.id) },
            onDismiss = { options = null },
        )
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextTertiary,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun CircleButton(res: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).softSurface(CircleShape, Palette.Surface, elevation = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        QuietIconButton(res, description, onClick = onClick, size = 44.dp, iconSize = 20.dp, tint = Palette.IconActive)
    }
}

@Composable
private fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(44.dp)
            .softSurface(RoundedCornerShape(50), Palette.Surface, elevation = 2.dp)
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
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("Search", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
                inner()
            },
        )
    }
}

@Composable
private fun ConversationRow(
    item: SessionSummary,
    running: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    onOptions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) Palette.Surface else Palette.Ink)
            .clickable(onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when {
                running -> RunningDot()
                item.pinned -> AppIcon(R.drawable.ic_pin, null, tint = Palette.IconMuted, size = 17.dp)
                else -> AppIcon(R.drawable.ic_check, null, tint = Palette.IconMuted, size = 17.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        FadingText(
            item.title.ifBlank { "Untitled" },
            MaterialTheme.typography.bodyMedium,
            Palette.TextPrimary,
            Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (running) "Working" else relativeTime(item.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextTertiary,
            maxLines = 1,
        )
        QuietIconButton(R.drawable.ic_more, "Conversation options", onClick = onOptions, tint = Palette.IconMuted, iconSize = 18.dp)
    }
}

@Composable
private fun RunningDot() {
    val t = rememberInfiniteTransition(label = "running")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(Palette.TextSecondary))
}

private fun relativeTime(at: Long): String {
    val age = (System.currentTimeMillis() - at).coerceAtLeast(0)
    return when {
        age < 60_000 -> "Just now"
        age < 3_600_000 -> "${age / 60_000} min ago"
        age < 86_400_000 -> "${age / 3_600_000} h ago"
        age < 7 * 86_400_000L -> "${age / 86_400_000} d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}
