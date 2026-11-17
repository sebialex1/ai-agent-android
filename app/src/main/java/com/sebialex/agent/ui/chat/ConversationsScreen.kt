package com.sebialex.agent.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
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
import com.sebialex.agent.ui.components.SurfaceIconButton
import com.sebialex.agent.ui.components.busyShimmer
import com.sebialex.agent.ui.components.fadeTopEdge
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.chatBackground
import com.sebialex.agent.ui.theme.softSurface

/** Unpinned rows shown before "Show more", and how many each tap reveals. */
private const val INITIAL_UNPINNED = 6
private const val MORE_STEP = 6

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
    val snap by settings.state.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf<SessionSummary?>(null) }
    val context = LocalContext.current
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // Selection mode: long-press a conversation, drag over more, tap to toggle, trash to delete.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { androidx.compose.runtime.snapshots.SnapshotStateList<String>() }
    fun exitSelection() {
        selecting = false
        selected.clear()
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            val next = (input.text + " " + it).trim()
            input = TextFieldValue(next, TextRange(next.length))
        }
    }
    val filtered = remember(state.items, input.text) {
        val q = input.text.trim()
        if (q.isEmpty()) state.items else state.items.filter { it.title.contains(q, ignoreCase = true) }
    }
    // The input doubles as the conversation search: the list filters while typing, and the
    // arrow starts a new conversation with whatever is written (the dedicated bar is gone).

    Column(
        Modifier
            .fillMaxSize()
            .chatBackground(snap.customBackground, snap.backgroundDim, snap.backgroundVersion)
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(R.drawable.ic_close, "Cancel selection") { exitSelection() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (selected.size == 1) "1 selected" else "${selected.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        color = Palette.TextPrimary,
                    )
                }
                CircleButton(R.drawable.ic_trash, "Delete selected") {
                    vm.deleteConversations(selected.toList())
                    exitSelection()
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(R.drawable.ic_settings, "Settings", onSettings)
                Spacer(Modifier.weight(1f))
                CircleButton(R.drawable.ic_new_chat, "New conversation", onNew)
            }
        }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        // Opening the list lands on the newest conversation instead of wherever it was left scrolled.
        androidx.compose.runtime.LaunchedEffect(Unit) { listState.scrollToItem(0) }
        var unpinnedShown by rememberSaveable { mutableStateOf(INITIAL_UNPINNED) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (filtered.isEmpty()) {
                Text(
                    if (input.text.isBlank()) "No conversations yet" else "No matches",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextTertiary,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // Pinned always show; the rest is revealed a screenful at a time.
                val groups = groupConversations(filtered)
                val pinnedRows = groups.firstOrNull { it.first == "Pinned" }?.second.orEmpty()
                val unpinned = groups.filterNot { it.first == "Pinned" }.flatMap { it.second }
                val visible = unpinned.take(unpinnedShown)
                val display = (if (pinnedRows.isEmpty()) emptyList() else listOf("Pinned" to pinnedRows)) +
                    groupConversations(visible)
                LazyColumn(
                    Modifier.fillMaxSize().fadeTopEdge(listState),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    display.forEach { (label, rows) ->
                        item(key = "h:$label") { ListHeader(label) }
                        items(rows, key = { (if (it.pinned) "p:" else "c:") + it.id }) { item ->
                            ConversationRow(
                                item = item,
                                running = item.id in state.runningIds,
                                current = item.id == state.currentId,
                                selected = selecting && item.id in selected,
                                selecting = selecting,
                                listState = listState,
                                onClick = {
                                    if (selecting) {
                                        if (item.id in selected) selected.remove(item.id) else selected.add(item.id)
                                    } else onOpen(item.id)
                                },
                                onLongPress = {
                                    if (!selecting) {
                                        selecting = true
                                        selected.add(item.id)
                                    }
                                },
                                onSelectMore = { id -> if (id.isNotBlank() && id !in selected) selected.add(id) },
                                onOptions = { options = item },
                            )
                        }
                    }
                    if (unpinned.size > visible.size) {
                        item(key = "more") {
                            Text(
                                "Show ${minOf(MORE_STEP, unpinned.size - visible.size)} more",
                                style = MaterialTheme.typography.labelMedium,
                                color = Palette.TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { unpinnedShown += MORE_STEP }
                                    .padding(horizontal = 12.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }

        val emoji = activeEmoji(input)
        EmojiPopup(
            entries = emoji?.let { suggestEmoji(it.query, snap.emojiRecent) }.orEmpty(),
            onPick = { e ->
                emoji?.let {
                    settings.recordEmojiUse(e.code)
                    input = applyEmoji(input, it, e.emoji)
                }
            },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        )
        InputPill(
            value = input,
            onValueChange = { input = withParsedEmojis(it) },
            running = false,
            onSend = {
                val text = parseEmojiCodes(input.text.trim())
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
            placeholder = "Search or start a conversation",
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

/** Pinned conversations first, then Today / Yesterday / Previous 7 days / Earlier, newest first. */
private fun groupConversations(items: List<SessionSummary>): List<Pair<String, List<SessionSummary>>> {
    val out = mutableListOf<Pair<String, List<SessionSummary>>>()
    val pinned = items.filter { it.pinned }
    if (pinned.isNotEmpty()) out += "Pinned" to pinned
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    val day = 86_400_000L
    fun bucket(at: Long) = when {
        at >= today -> "Today"
        at >= today - day -> "Yesterday"
        at >= today - 6 * day -> "Previous 7 days"
        else -> "Earlier"
    }
    items.filterNot { it.pinned }.sortedByDescending { it.updatedAt }.groupBy { bucket(it.updatedAt) }.forEach { (k, v) -> out += k to v }
    return out
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextTertiary,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun CircleButton(res: Int, description: String, onClick: () -> Unit) {
    SurfaceIconButton(res, description, onClick = onClick, tint = Palette.IconActive)
}

/** The item id a list key belongs to; empty for headers. */
private fun idOfKey(key: Any): String = (key as? String)?.let { k ->
    when {
        k.startsWith("c:") -> k.removePrefix("c:")
        k.startsWith("p:") -> k.removePrefix("p:")
        else -> ""
    }
}.orEmpty()

/**
 * One conversation: the title on top, a quiet line below with when it was updated and how many
 * messages it holds. Only pinned chats carry an icon; the open chat gets a soft background.
 * Long-press enters selection mode; dragging from a long-press selects every row it passes.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: SessionSummary,
    running: Boolean,
    current: Boolean,
    selected: Boolean,
    selecting: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSelectMore: (String) -> Unit,
    onOptions: () -> Unit,
) {
    val key = (if (item.pinned) "p:" else "c:") + item.id
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(when {
                selected -> Palette.Accent.copy(alpha = 0.16f)
                current -> Palette.Surface
                else -> Color.Transparent
            })
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .then(
                if (!selecting) Modifier
                else Modifier.pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onSelectMore(item.id) },
                        onDrag = { change, _ ->
                            change.consume()
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                            val absY = info.offset + change.position.y
                            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { absY >= it.offset && absY < it.offset + it.size } ?: return@detectDragGesturesAfterLongPress
                            onSelectMore(idOfKey(target.key))
                        },
                    )
                },
            )
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // A chat that is still generating gets a soft moving shimmer on its title (no dot, no label).
            FadingText(
                item.title.ifBlank { "Untitled" },
                MaterialTheme.typography.bodyLarge,
                Palette.TextPrimary,
                if (running) Modifier.processingShimmer() else Modifier,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.pinned) {
                    AppIcon(R.drawable.ic_pin, "Pinned", tint = Palette.IconMuted, size = 12.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    listOfNotNull(
                        relativeTime(item.updatedAt),
                        item.turnCount.takeIf { it > 0 }?.let { if (it == 1) "1 message" else "$it messages" },
                    ).joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                    maxLines = 1,
                )
            }
        }
        if (selecting) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(Palette.Accent, CircleShape)
                        else Modifier.border(1.5.dp, Palette.IconMuted.copy(alpha = 0.55f), CircleShape)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) AppIcon(R.drawable.ic_check, "Selected", tint = Palette.OnAccent, size = 13.dp)
            }
        } else {
            QuietIconButton(R.drawable.ic_more, "Conversation options", onClick = onOptions, tint = Palette.IconMuted, iconSize = 18.dp)
        }
    }
}

/**
 * Title shimmer for a conversation that is generating: the shared busy gradient (see
 * [com.sebialex.agent.ui.components.busyShimmer]), so a running chat and a running step move alike.
 */
@Composable
private fun Modifier.processingShimmer(): Modifier = busyShimmer()

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
