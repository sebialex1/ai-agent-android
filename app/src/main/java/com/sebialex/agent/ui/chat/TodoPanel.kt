package com.sebialex.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.agent.Todos
import com.sebialex.agent.domain.model.TodoItem
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette

/**
 * The conversation's todo list (kept by the agent's todo tool) as the top section of the chatbox
 * itself: drawn inside the input's own surface (see [InputPill]'s `top`), above a hairline, so
 * it reads as part of the box rather than a separate card. Collapsed it is one slim row: a
 * checkbox mark, the next open entry (shortened in the middle so its start and end stay
 * readable), the done count and a chevron. Expanded it lists every entry; done ones turn gray
 * and are struck through. Tapping an entry ticks it. It never covers the text field, so typing
 * is never blocked. Only passed to the input while the list has entries ([TodoStrip.visible]).
 */
@Composable
fun TodoPanel(
    sessionId: String,
    items: List<TodoItem>,
    onToggle: (Int) -> Unit,
    onClearDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!TodoStrip.visible(items)) return
    var open by rememberSaveable(sessionId, key = "todos:$sessionId") { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 0f else 180f, animationSpec = tween(180), label = "todoArrow")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Hide todo list" else "Show todo list") { open = !open }
                .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = if (open) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                if (TodoStrip.allDone(items)) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox, null,
                tint = Palette.IconMuted, size = 14.dp,
            )
            Spacer(Modifier.width(8.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                // labelSmall is roughly 6.5 dp per character: enough to shorten in the middle without measuring.
                val fit = (maxWidth.value / 6.5f).toInt().coerceAtLeast(12)
                Text(
                    if (open) TodoStrip.title(items) else TodoStrip.hint(items, fit),
                    style = if (open) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                    color = if (open) Palette.TextSecondary else Palette.TextTertiary,
                    maxLines = 1,
                )
            }
            if (open && items.any { it.done }) {
                Text(
                    "Clear done",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClearDone).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else if (!open) {
                Text(TodoStrip.count(items), style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
            }
            Spacer(Modifier.width(6.dp))
            AppIcon(R.drawable.ic_chevron_down, null, tint = Palette.IconMuted, size = 14.dp, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 12.dp, bottom = 8.dp)) {
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onToggle(item.id) }.padding(horizontal = 4.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        AppIcon(
                            if (item.done) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox,
                            if (item.done) "Done" else "Open",
                            tint = if (item.done) Palette.TextTertiary else Palette.IconMuted,
                            size = 16.dp,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.done) Palette.TextTertiary else Palette.TextPrimary,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
    }
}

/** What the todo section of the chatbox shows, kept out of Compose so it is unit-tested. */
object TodoStrip {
    /** The section exists only while the list has entries; otherwise the input is the plain pill. */
    fun visible(items: List<TodoItem>): Boolean = items.isNotEmpty()

    fun allDone(items: List<TodoItem>): Boolean = items.isNotEmpty() && items.all { it.done }

    /** Collapsed: the next open entry, shortened in the middle to about [fit] characters. */
    fun hint(items: List<TodoItem>, fit: Int): String =
        Todos.nextOpen(items)?.let { "Next: " + middleEllipsis(it.text, (fit - 6).coerceAtLeast(5)) } ?: "All ${items.size} done"

    /** Collapsed, at the end of the row: "2/5". */
    fun count(items: List<TodoItem>): String = "${items.count { it.done }}/${items.size}"

    /** Expanded: the section's heading. */
    fun title(items: List<TodoItem>): String = "Todo \u00b7 ${items.count { it.done }} of ${items.size} done"
}

/** Shortens [text] to [max] characters by cutting out its middle ("Write the intro…final section"). */
internal fun middleEllipsis(text: String, max: Int): String {
    val t = text.replace('\n', ' ').trim()
    if (t.length <= max || max < 5) return t
    val head = (max - 1) / 2 + (max - 1) % 2
    val tail = (max - 1) / 2
    return t.take(head).trimEnd() + "\u2026" + t.takeLast(tail).trimStart()
}
