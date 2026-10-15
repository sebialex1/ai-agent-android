package com.sebialex.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.agent.Todos
import com.sebialex.agent.domain.model.TodoItem
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.theme.Palette

/**
 * The conversation's todo list (kept by the agent's todo tool), as a panel that comes out of
 * the top of the input bar. Collapsed it is a slim strip tucked behind the bar: the arrow in
 * the middle and the next open entry as a hint, shortened in the middle so its start and end
 * both stay readable. Expanded it lists every entry as plain text; done ones turn gray and are
 * struck through. Tapping an entry ticks it. Nothing of it covers the text field, so typing is
 * never blocked. Hidden while the list is empty.
 */
@Composable
fun TodoPanel(
    sessionId: String,
    items: List<TodoItem>,
    onToggle: (Int) -> Unit,
    onClearDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    var open by rememberSaveable(sessionId, key = "todos:$sessionId") { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 0f else 180f, animationSpec = tween(180), label = "todoArrow")
    val done = items.count { it.done }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            // Tucked a few dp under the bar (drawn after it), so it reads as coming out of it.
            .offset(y = TUCK)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Palette.Surface)
            .animateContentSize(tween(200)),
    ) {
        Box(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 3.dp, bottom = if (open) 2.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                R.drawable.ic_chevron_down, if (open) "Hide todo list" else "Show todo list",
                tint = Palette.IconMuted, size = 15.dp, modifier = Modifier.rotate(rotation),
            )
        }
        if (!open) {
            val next = Todos.nextOpen(items)
            BoxWithConstraints(Modifier.fillMaxWidth().clickable { open = true }.padding(start = 14.dp, end = 14.dp, bottom = TUCK + 4.dp)) {
                // labelSmall is roughly 6.5 dp per character: enough to shorten in the middle without measuring.
                val fit = (maxWidth.value / 6.5f).toInt().coerceAtLeast(12)
                Text(
                    next?.let { middleEllipsis(it.text, fit - 6) }?.let { "Next: $it" } ?: "All ${items.size} done",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 10.dp, bottom = TUCK + 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Todo \u00b7 $done of ${items.size} done", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
                    if (done > 0) Text(
                        "Clear done",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.TextTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClearDone).padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggle(item.id) }.padding(vertical = 5.dp),
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
}

/** How far the panel slides under the input bar. */
private val TUCK = 8.dp

/** Shortens [text] to [max] characters by cutting out its middle ("Write the intro…final section"). */
internal fun middleEllipsis(text: String, max: Int): String {
    val t = text.replace('\n', ' ').trim()
    if (t.length <= max || max < 5) return t
    val head = (max - 1) / 2 + (max - 1) % 2
    val tail = (max - 1) / 2
    return t.take(head).trimEnd() + "\u2026" + t.takeLast(tail).trimStart()
}
