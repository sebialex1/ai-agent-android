package com.sebialex.agent.ui.terminal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.shell.Shell
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.ui.chat.SourcePreviewSheet
import com.sebialex.agent.ui.components.Favicon
import com.sebialex.agent.domain.permission.PermissionDecision
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.ui.chat.PillButton
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.FadingText
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.theme.MonoSmall
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Entry(val cwd: String, val command: String, val output: String, val code: Int)

/** Sections of the workspace, shown as icon tabs in the top bar (terminal, sources, files). */
enum class WorkspaceTab(val icon: Int, val label: String) {
    TERMINAL(R.drawable.ic_terminal, "Terminal"),
    SOURCES(R.drawable.ic_globe, "Sources"),
    FILES(R.drawable.ic_folder, "Files"),
}

/**
 * The conversation's workspace, laid out like Claude/Perplexity's task view: a segmented icon
 * switch at the top for the terminal, the web sources that were read and the files.
 * Sources and Files only appear when there is something in them; an empty section is never
 * rendered. Artifacts (files the agent wrote) get their own group above the raw workspace files.
 *
 * The terminal is the same sandbox and permission policy the agent uses; risky commands need an
 * explicit confirmation before they run.
 */
@Composable
fun WorkspaceScreen(
    shell: Shell,
    policy: PermissionPolicy,
    workspace: Workspace,
    sources: List<SourceRef>,
    artifacts: List<FileAttachment>,
    onBack: () -> Unit,
    onOpenFile: (path: String, baseSnapshot: String?) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Terminal state lives here so switching tabs never loses the session.
    val entries = remember { mutableStateListOf<Entry>() }
    var cwd by rememberSaveable { mutableStateOf("/") }
    var input by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tab by rememberSaveable { mutableStateOf(WorkspaceTab.TERMINAL) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var preview by remember { mutableStateOf<SourceRef?>(null) }

    fun refreshFiles() {
        scope.launch {
            files = withContext(Dispatchers.IO) {
                runCatching {
                    workspace.walk().map { workspace.virtualOf(it) }
                        .filterNot { p -> p.split('/').any { it.startsWith(".") } }
                        .sorted()
                }.getOrDefault(emptyList())
            }
        }
    }

    fun run(command: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { shell.execute(command, cwd) }
            if (r.output == "\u000c") entries.clear() else entries += Entry(cwd, command, r.output, r.exitCode)
            cwd = r.cwd
            r.openFile?.let { onOpenFile(it, null) }
            refreshFiles()
        }
    }

    fun submit() {
        val command = input.trim()
        if (command.isEmpty()) return
        input = ""
        when (val d = policy.evaluateShell(command)) {
            PermissionDecision.Allow -> run(command)
            is PermissionDecision.Ask -> pending = command to d.reason.label
            is PermissionDecision.Deny -> entries += Entry(cwd, command, "blocked: ${d.reason.label}", 1)
        }
    }

    LaunchedEffect(Unit) {
        refreshFiles()
        if (entries.isEmpty()) run("ls")
    }

    // Only sections with content are offered.
    val distinctSources = remember(sources) { sources.distinctBy { it.url } }
    val distinctArtifacts = remember(artifacts) {
        artifacts.groupBy { it.path }.map { (_, versions) -> versions.maxBy { it.updatedAt } }.sortedByDescending { it.updatedAt }
    }
    val tabs = buildList {
        add(WorkspaceTab.TERMINAL)
        if (distinctSources.isNotEmpty()) add(WorkspaceTab.SOURCES)
        if (distinctArtifacts.isNotEmpty() || files.isNotEmpty()) add(WorkspaceTab.FILES)
    }
    val current = if (tab in tabs) tab else WorkspaceTab.TERMINAL

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp)) {
            CircleIconButton(R.drawable.ic_close, "Back", onBack, Modifier.align(Alignment.CenterStart))
            if (tabs.size > 1) {
                SegmentedTabs(tabs, current, onSelect = { tab = it }, modifier = Modifier.align(Alignment.Center))
            } else {
                Text("Workspace", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, modifier = Modifier.align(Alignment.Center))
            }
            if (current == WorkspaceTab.TERMINAL) {
                CircleIconButton(R.drawable.ic_folder, "List files", { run("tree") }, Modifier.align(Alignment.CenterEnd))
            }
        }
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "workspaceTab",
        ) { t ->
            when (t) {
                WorkspaceTab.TERMINAL -> TerminalTab(
                    entries = entries,
                    cwd = cwd,
                    input = input,
                    onInput = { input = it },
                    pending = pending,
                    onCancelPending = { pending = null },
                    onRunPending = { c -> pending = null; run(c) },
                    onSubmit = ::submit,
                )
                WorkspaceTab.SOURCES -> SourcesTab(distinctSources, onOpen = { preview = it })
                WorkspaceTab.FILES -> FilesTab(distinctArtifacts, files, onOpenFile)
            }
        }
    }

    preview?.let { src ->
        SourcePreviewSheet(src, onDismiss = { preview = null }, onOpenWebsite = { preview = null; onOpenUrl(it.url) })
    }
}

@Composable
private fun CircleIconButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .softSurface(RoundedCornerShape(50), Palette.SurfaceHigh, elevation = 2.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, description, tint = Palette.IconActive, size = 20.dp)
    }
}

@Composable
private fun SegmentedTabs(tabs: List<WorkspaceTab>, selected: WorkspaceTab, onSelect: (WorkspaceTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .softSurface(RoundedCornerShape(50), Palette.SurfaceHigh, elevation = 2.dp)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val on = t == selected
            Box(
                Modifier
                    .size(width = 52.dp, height = 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Palette.SurfaceHighest else Palette.SurfaceHigh)
                    .clickable { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(t.icon, t.label, tint = if (on) Palette.IconActive else Palette.IconMuted, size = 18.dp)
            }
        }
    }
}

@Composable
private fun TerminalTab(
    entries: List<Entry>,
    cwd: String,
    input: String,
    onInput: (String) -> Unit,
    pending: Pair<String, String>?,
    onCancelPending: () -> Unit,
    onRunPending: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries) { e ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Palette.CodeBackground)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "/workspace" + if (e.cwd == "/") "" else e.cwd,
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.TextTertiary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (e.code != 0) Text("exit ${e.code}", style = MaterialTheme.typography.labelSmall, color = Palette.Danger)
                        }
                        Text("$ ${e.command}", style = MonoSmall, color = Palette.TextPrimary, modifier = Modifier.padding(top = 4.dp))
                        if (e.output.isNotEmpty()) {
                            Text(e.output, style = MonoSmall, color = if (e.code == 0) Palette.TextSecondary else Palette.Danger, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        pending?.let { (command, reason) ->
            Column(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .softSurface(RoundedCornerShape(20.dp), Palette.Surface, elevation = 4.dp)
                    .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_shield, null, tint = Palette.Warning, size = 18.dp)
                    Spacer(Modifier.width(12.dp))
                    FadingText(command, MonoSmall, Palette.TextPrimary, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(reason, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary, modifier = Modifier.padding(start = 30.dp).weight(1f))
                    PillButton("Cancel", primary = false) { onCancelPending() }
                    Spacer(Modifier.width(8.dp))
                    PillButton("Run", primary = true) { onRunPending(command) }
                }
            }
        }
        Row(
            Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp)
                .fillMaxWidth()
                .softSurface(RoundedCornerShape(28.dp), Palette.SurfaceHigh, elevation = 10.dp)
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", style = MonoSmall, color = Palette.TextTertiary)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = input,
                onValueChange = onInput,
                singleLine = true,
                textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                decorationBox = { inner ->
                    if (input.isEmpty()) Text("Run a command in " + "/workspace" + if (cwd == "/") "" else cwd, style = MonoSmall, color = Palette.TextTertiary, maxLines = 1)
                    inner()
                },
            )
            QuietIconButton(R.drawable.ic_arrow_up, "Run", onClick = onSubmit, enabled = input.isNotBlank())
        }
    }
}

/** Collapsible group header in the style of the reference: icon, label, count, chevron. */
@Composable
private fun GroupHeader(icon: Int, label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, animationSpec = tween(180), label = "groupChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, null, tint = Palette.IconActive, size = 20.dp)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.bodyMedium, color = Palette.TextTertiary)
        Spacer(Modifier.width(8.dp))
        AppIcon(R.drawable.ic_chevron_right, null, tint = Palette.TextTertiary, size = 16.dp, modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun SourcesTab(sources: List<SourceRef>, onOpen: (SourceRef) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item { GroupHeader(R.drawable.ic_globe, "Web", sources.size, expanded) { expanded = !expanded } }
        if (expanded) items(sources, key = { it.url }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpen(s) }
                    .padding(start = 44.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Favicon(s.host, size = 16.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    FadingText(s.title.ifBlank { s.host }, MaterialTheme.typography.bodyMedium, Palette.TextPrimary)
                    FadingText(s.host, MaterialTheme.typography.labelSmall, Palette.TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun FilesTab(artifacts: List<FileAttachment>, files: List<String>, onOpenFile: (String, String?) -> Unit) {
    var artifactsOpen by rememberSaveable { mutableStateOf(true) }
    var filesOpen by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        // Artifacts: only rendered when the agent actually produced at least one.
        if (artifacts.isNotEmpty()) {
            item { GroupHeader(R.drawable.ic_file_edit, "Artifacts", artifacts.size, artifactsOpen) { artifactsOpen = !artifactsOpen } }
            if (artifactsOpen) items(artifacts, key = { "a:" + it.path }) { a ->
                FileRow(
                    name = a.name,
                    detail = a.path + if (a.baseSnapshot != null) "   +${a.added} \u2212${a.removed}" else "",
                    onClick = { onOpenFile(a.path, a.baseSnapshot) },
                )
            }
        }
        if (files.isNotEmpty()) {
            item { GroupHeader(R.drawable.ic_folder, "Workspace", files.size, filesOpen) { filesOpen = !filesOpen } }
            if (filesOpen) items(files, key = { "f:$it" }) { path ->
                FileRow(name = path.substringAfterLast('/'), detail = path, onClick = { onOpenFile(path, null) })
            }
        }
    }
}

@Composable
private fun FileRow(name: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 44.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_file, null, tint = Palette.IconMuted, size = 18.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            FadingText(name, MaterialTheme.typography.bodyMedium, Palette.TextPrimary)
            FadingText(detail, MaterialTheme.typography.labelSmall, Palette.TextTertiary)
        }
    }
}
