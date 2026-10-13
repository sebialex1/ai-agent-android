package com.sebialex.agent.ui.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.markdown.DiffLine
import com.sebialex.agent.domain.markdown.DiffType
import com.sebialex.agent.domain.markdown.LineDiff
import com.sebialex.agent.domain.markdown.MarkdownParser
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.markdown.MarkdownBlockView
import com.sebialex.agent.ui.theme.MonoBody
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ViewMode { RENDERED, RAW, DIFF, EDIT }

@Composable
fun FileViewerScreen(
    workspace: Workspace,
    path: String,
    baseSnapshot: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val name = path.substringAfterLast('/')
    val isMarkdown = name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true)
    var content by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(if (isMarkdown) ViewMode.RENDERED else ViewMode.RAW) }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(path, baseSnapshot) {
        withContext(Dispatchers.IO) {
            runCatching { workspace.read(path) }
                .onSuccess { content = it }
                .onFailure { error = it.message }
            base = baseSnapshot?.let { workspace.snapshot(it) }
        }
    }

    fun save(text: String) {
        scope.launch {
            withContext(Dispatchers.IO) { workspace.write(path, text) }
            content = text
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode == ViewMode.EDIT) {
                QuietIconButton(R.drawable.ic_close, "Discard changes", onClick = { mode = if (isMarkdown) ViewMode.RENDERED else ViewMode.RAW })
            } else {
                QuietIconButton(R.drawable.ic_back, "Back", onClick = onBack)
            }
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (mode == ViewMode.EDIT) {
                QuietIconButton(R.drawable.ic_check, "Save", onClick = {
                    save(draft)
                    mode = if (isMarkdown) ViewMode.RENDERED else ViewMode.RAW
                })
            } else if (content != null) {
                if (base != null) {
                    QuietIconButton(
                        R.drawable.ic_diff, "Changes",
                        onClick = { mode = if (mode == ViewMode.DIFF) ViewMode.RENDERED else ViewMode.DIFF },
                        tint = if (mode == ViewMode.DIFF) Palette.IconActive else Palette.IconMuted,
                    )
                }
                if (isMarkdown) {
                    QuietIconButton(
                        R.drawable.ic_code, "Raw",
                        onClick = { mode = if (mode == ViewMode.RAW) ViewMode.RENDERED else ViewMode.RAW },
                        tint = if (mode == ViewMode.RAW) Palette.IconActive else Palette.IconMuted,
                    )
                }
                QuietIconButton(R.drawable.ic_edit, "Edit", onClick = { draft = content ?: ""; mode = ViewMode.EDIT }, tint = Palette.IconMuted)
            }
        }

        val text = content
        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
            }
            text == null -> Spacer(Modifier.fillMaxSize())
            else -> AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "mode",
                modifier = Modifier.weight(1f),
            ) { m ->
                when (m) {
                    ViewMode.RENDERED -> RenderedMarkdown(text, onToggleTask = { line -> save(MarkdownParser.toggleTask(text, line)) })
                    ViewMode.RAW -> RawText(text)
                    ViewMode.DIFF -> DiffView(base ?: "", text)
                    ViewMode.EDIT -> Editor(draft, onChange = { draft = it })
                }
            }
        }
    }
}

@Composable
private fun RenderedMarkdown(source: String, onToggleTask: (Int) -> Unit) {
    val blocks = remember(source) { MarkdownParser.parse(source) }
    LazyColumn(
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(blocks.size) { i -> MarkdownBlockView(blocks[i], compact = false, onToggleTask = onToggleTask) }
    }
}

@Composable
private fun RawText(source: String) {
    SelectionContainer {
        Text(
            source,
            style = MonoBody,
            color = Palette.TextSecondary,
            softWrap = false,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Editor(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = MonoBody.copy(color = Palette.TextPrimary),
        cursorBrush = SolidColor(Palette.TextPrimary),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 8.dp),
    )
}

@Composable
private fun DiffView(old: String, new: String) {
    val lines = remember(old, new) { LineDiff.diff(old, new) }
    val added = lines.count { it.type == DiffType.ADDED }
    val removed = lines.count { it.type == DiffType.REMOVED }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
            Text("+$added", style = MaterialTheme.typography.labelMedium, color = Palette.DiffAddText)
            Spacer(Modifier.width(10.dp))
            Text("\u2212$removed", style = MaterialTheme.typography.labelMedium, color = Palette.DiffRemoveText)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            items(lines.size) { i -> DiffRow(lines[i]) }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val (bg, fg, sign) = when (line.type) {
        DiffType.ADDED -> Triple(Palette.DiffAdd, Palette.DiffAddText, "+")
        DiffType.REMOVED -> Triple(Palette.DiffRemove, Palette.DiffRemoveText, "\u2212")
        DiffType.SAME -> Triple(Palette.Ink, Palette.TextSecondary, " ")
    }
    Row(Modifier.fillMaxWidth().background(bg).padding(start = 14.dp, end = 22.dp, top = 1.dp, bottom = 1.dp)) {
        Text(
            (line.newNo ?: line.oldNo)?.toString() ?: "",
            style = MonoSmall,
            color = Palette.TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(sign, style = MonoSmall, color = fg, modifier = Modifier.width(14.dp))
        Text(line.text.ifEmpty { " " }, style = MonoSmall, color = fg, modifier = Modifier.weight(1f))
    }
}
