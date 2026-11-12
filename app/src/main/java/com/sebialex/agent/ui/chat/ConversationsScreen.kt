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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
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
import com.sebialex.agent.ui.components.fadeTopEdge
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.chatBackground
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
    val snap by settings.state.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf<SessionSummary?>(null) }
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
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
    val filtered = remember(state.items, query) {
        val q = query.trim()
        if (q.isEmpty()) state.items else state.items.filter { it.title.contains(q, ignoreCase = true) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .chatBackground(snap.customBackground, snap.backgroundDim)
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
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
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(R.drawable.ic_settings, "Settings", onSettings)
                Spacer(Modifier.width(10.dp))
                SearchPill(query, { query = it }, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                CircleButton(R.drawable.ic_new_chat, "New conversation", onNew)
            }
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
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    Modifier.fillMaxSize().fadeTopEdge(listState),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    // Pinned first, then by how recently each conversation was updated.
                    val groups = groupConversations(filtered)
                    groups.forEach { (label, rows) ->
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
    QuietIconButton(res, description, onClick = onClick, size = 44.dp, iconSize = 21.dp, tint = Palette.IconActive)
}

/** The item id a list key belongs to; empty for headers. */
private fun idOfKey(key: Any): String = (key as? String)?.let { k ->
    when {
        k.startsWith("c:") -> k.removePrefix("c:")
        k.startsWith("p:") -> k.removePrefix("p:")
        else -> ""
    }
}.orEmpty()

@Composable
private fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface)
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
                if (value.isEmpty()) Text("Search conversations", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary, maxLines = 1)
                inner()
            },
        )
    }
}

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
                if (selected) AppIcon(R.drawable.ic_check, "Selected", tint = Color.White, size = 13.dp)
            }
        } else {
            QuietIconButton(R.drawable.ic_more, "Conversation options", onClick = onOptions, tint = Palette.IconMuted, iconSize = 18.dp)
        }
    }
}

/**
 * Title shimmer for a conversation that is generating: the glyphs are recolored (SrcIn on an
 * offscreen layer) with a gray gradient whose lighter band drifts slowly from left to right.
 * Gray enough to read as "busy", light enough to stay legible. Only used in this list.
 */
@Composable
private fun Modifier.processingShimmer(): Modifier {
    val t = rememberInfiniteTransition(label = "processing")
    val progress by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    return this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val band = (size.width * 0.55f).coerceAtLeast(80.dp.toPx())
            // Read in the draw phase only, so the animation never recomposes the row.
            val x = -band + (size.width + band * 2f) * progress
            drawRect(
                brush = Brush.linearGradient(
                    0f to ShimmerBase,
                    0.5f to ShimmerPeak,
                    1f to ShimmerBase,
                    start = Offset(x, 0f),
                    end = Offset(x + band, 0f),
                    tileMode = TileMode.Clamp,
                ),
                blendMode = BlendMode.SrcIn,
            )
        }
}

/** Resting gray of a busy title, and the lighter band that moves across it. */
private val ShimmerBase = Color(0xFF8C877F)
private val ShimmerPeak = Color(0xFFE6E1DA)

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
