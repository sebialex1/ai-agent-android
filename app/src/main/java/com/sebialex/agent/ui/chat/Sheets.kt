package com.sebialex.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.Favicon
import com.sebialex.agent.ui.components.FadingText
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Raw audit view for a single action. Only reachable by tapping a step row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepDetailSheet(
    step: Step,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenPath: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        scrimColor = Palette.Ink.copy(alpha = 0.6f),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val reasoning = step.kind == StepKind.REASONING
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!reasoning) {
                    if (step.kind == StepKind.FETCH) Favicon(step.payload, size = 18.dp)
                    else AppIcon(iconFor(step.kind), null, tint = Palette.IconActive, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(step.tool, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                QuietIconButton(R.drawable.ic_copy, "Copy", onClick = {
                    clipboard.setText(AnnotatedString(if (reasoning) step.output else step.input + "\n\n" + step.output))
                }, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
            }
            if (reasoning) {
                // The model's raw reasoning, as returned. No timing, no status.
                RawBlock("raw", step.output, wrap = true)
            } else {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Meta("status", step.status.name.lowercase(Locale.US).replace('_', ' '))
                    step.risk?.let { Meta("risk", it) }
                    if (step.autoApproved) Meta("approval", "auto-approved")
                    Meta("started", time.format(Date(step.startedAt)))
                    step.finishedAt?.let { Meta("duration", "${it - step.startedAt} ms") }
                    step.exitCode?.let { Meta("exit", it.toString()) }
                }
                step.url?.let { url ->
                    LinkRow(R.drawable.ic_open_external, url) { onOpenUrl(url) }
                }
                if (step.url == null) step.path?.let { path ->
                    LinkRow(R.drawable.ic_file, path) { onOpenPath(path) }
                }
                RawBlock("input", step.input)
                if (step.output.isNotEmpty()) RawBlock("output", step.output)
            }
        }
    }
}

@Composable
private fun Meta(key: String, value: String) {
    Row {
        Text(key, style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
    }
}

@Composable
private fun LinkRow(icon: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FadingText(text, MonoSmall, Palette.TextPrimary, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        AppIcon(icon, null, size = 16.dp)
    }
}

@Composable
private fun RawBlock(label: String, text: String, wrap: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.CodeBackground)
                .verticalScroll(rememberScrollState())
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(14.dp),
        ) {
            SelectionContainer {
                Text(text, style = MonoSmall, color = Palette.TextSecondary, softWrap = wrap)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesSheet(sources: List<SourceRef>, onDismiss: () -> Unit, onOpen: (SourceRef) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        scrimColor = Palette.Ink.copy(alpha = 0.6f),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 20.dp),
        ) {
            sources.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpen(s) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Favicon(s.host, size = 18.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        FadingText(s.title.ifBlank { s.host }, MaterialTheme.typography.bodyMedium, Palette.TextPrimary)
                        FadingText(s.url.removePrefix("https://"), MaterialTheme.typography.labelSmall, Palette.TextTertiary)
                    }
                }
            }
        }
    }
}

/**
 * Compact preview shown when a web source is tapped: favicon, title, domain and an excerpt of
 * what was read. Nothing is opened until the user taps "Open website".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePreviewSheet(source: SourceRef, onDismiss: () -> Unit, onOpenWebsite: (SourceRef) -> Unit) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Surface,
        scrimColor = Palette.Ink.copy(alpha = 0.6f),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Favicon(source.host, size = 22.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        source.title.ifBlank { source.host },
                        style = MaterialTheme.typography.titleSmall,
                        color = Palette.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(source.host, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, maxLines = 1)
                }
                QuietIconButton(R.drawable.ic_copy, "Copy link", onClick = {
                    clipboard.setText(AnnotatedString(source.url))
                }, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
            }
            if (source.snippet.isNotBlank()) {
                Text(
                    source.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FadingText(source.url.removePrefix("https://").removePrefix("http://"), MonoSmall, Palette.TextTertiary)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Accent)
                    .clickable { onOpenWebsite(source) }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Open website", style = MaterialTheme.typography.labelLarge, color = Palette.OnAccent)
                Spacer(Modifier.width(8.dp))
                AppIcon(R.drawable.ic_open_external, null, tint = Palette.OnAccent, size = 16.dp)
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.SurfaceHighest))
    }
}
