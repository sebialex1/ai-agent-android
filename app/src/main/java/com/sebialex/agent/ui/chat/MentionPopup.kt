package com.sebialex.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.sebialex.agent.domain.agent.Mentions
import com.sebialex.agent.ui.files.FileKind
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface

/** The `@query` being typed at the cursor: where its `@` is and what follows it. */
data class ActiveMention(val start: Int, val end: Int, val query: String)

/**
 * The mention under the cursor, or null. A mention starts with `@` at the beginning of the text
 * or after whitespace (so e-mail addresses never open the popup) and runs to the cursor without
 * whitespace. Only a collapsed cursor counts.
 */
fun activeMention(value: TextFieldValue): ActiveMention? {
    val sel = value.selection
    if (!sel.collapsed) return null
    val text = value.text
    val cursor = sel.end.coerceIn(0, text.length)
    var i = cursor - 1
    while (i >= 0 && !text[i].isWhitespace() && text[i] != '@') i--
    if (i < 0 || text[i] != '@') return null
    if (i > 0 && !text[i - 1].isWhitespace()) return null
    val query = text.substring(i + 1, cursor)
    if (query.startsWith("\"")) return null
    return ActiveMention(i, cursor, query)
}

/** Replaces the typed `@query` with the mention of [path] and a trailing space. */
fun applyMention(value: TextFieldValue, mention: ActiveMention, path: String): TextFieldValue {
    val insert = Mentions.format(path) + " "
    val text = value.text
    // Keep whatever follows the cursor; drop a space that would double up.
    val tail = text.substring(mention.end).let { if (it.startsWith(" ")) it.drop(1) else it }
    val next = text.substring(0, mention.start) + insert + tail
    return TextFieldValue(next, TextRange(mention.start + insert.length))
}

/** Candidates that match [query] (file name first, then anywhere in the path), best first. */
fun filterMentions(all: List<MentionCandidate>, query: String): List<MentionCandidate> {
    if (query.isEmpty()) return all.take(MAX_SHOWN)
    val q = query.lowercase()
    return all.mapNotNull { c ->
        val name = c.file.name.lowercase()
        val rank = when {
            name.startsWith(q) -> 0
            name.contains(q) -> 1
            c.path.lowercase().contains(q) -> 2
            else -> return@mapNotNull null
        }
        rank to c
    }.sortedBy { it.first }.map { it.second }.take(MAX_SHOWN)
}

private const val MAX_SHOWN = 40

/**
 * The floating suggestion card that opens above the input when you type `@`: a soft rounded
 * panel with a small header, then one row per file (thumbnail for images and videos, a type
 * tile otherwise; the name with the typed part emphasised; its folder and size; an "Artifact"
 * tag for files the agent produced). Newest artifacts first; scrolls when long.
 */
@Composable
fun MentionPopup(
    items: List<MentionCandidate>,
    query: String,
    loading: Boolean,
    onPick: (MentionCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .softSurface(shape, Palette.Surface, elevation = 12.dp)
            .clip(shape)
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (query.isEmpty()) "Mention a file" else "Files matching \u201c$query\u201d",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        when {
            items.isEmpty() -> Text(
                if (loading) "Looking through the workspace\u2026" else if (query.isEmpty()) "No files in this chat's workspace yet" else "No matching files",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            else -> LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 292.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.path }) { c -> MentionRow(c, query, onClick = { onPick(c) }) }
            }
        }
    }
}

@Composable
private fun MentionRow(c: MentionCandidate, query: String, onClick: () -> Unit) {
    val kind = FileKind.of(c.file.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Media get a real thumbnail on the left; everything else a quiet type tile.
        if (kind == FileKind.IMAGE || kind == FileKind.VIDEO) {
            MediaThumb(c.file, 38.dp, corner = 10.dp)
        } else {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
                Text(
                    c.file.extension.uppercase().take(4).ifEmpty { "FILE" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                highlighted(c.file.name, query),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val folder = c.path.substringBeforeLast('/', "").ifEmpty { "workspace" }
            Text(
                "$folder \u00b7 ${formatBytes(c.file.length())}",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.artifact) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Artifact",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.SurfaceHighest)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Fades and lifts the popup in above the input (kept out of any Column scope on purpose). */
@Composable
fun MentionOverlay(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it / 8 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)),
        modifier = modifier,
    ) { content() }
}

/** The file name with the typed part in bold. */
private fun highlighted(name: String, query: String) = buildAnnotatedString {
    val i = if (query.isEmpty()) -1 else name.indexOf(query, ignoreCase = true)
    if (i < 0) {
        append(name)
    } else {
        append(name.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.Accent)) { append(name.substring(i, i + query.length)) }
        append(name.substring(i + query.length))
    }
}
