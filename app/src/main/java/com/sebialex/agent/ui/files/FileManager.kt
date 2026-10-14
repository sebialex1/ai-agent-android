package com.sebialex.agent.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.workspace.Downloads
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.ui.components.AppIcon
import com.sebialex.agent.ui.components.AppSheet
import com.sebialex.agent.ui.components.FadingText
import com.sebialex.agent.ui.components.QuietIconButton
import com.sebialex.agent.ui.components.SheetConfirm
import com.sebialex.agent.ui.components.SheetDivider
import com.sebialex.agent.ui.components.SheetOption
import com.sebialex.agent.ui.components.SheetSwitch
import com.sebialex.agent.ui.components.SheetTextField
import com.sebialex.agent.ui.components.fadeTopEdge
import com.sebialex.agent.ui.theme.Palette
import com.sebialex.agent.ui.theme.softSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row of the current folder. */
private data class Item(val name: String, val path: String, val dir: Boolean, val size: Long, val modified: Long, val children: Int)

private sealed interface Action {
    data class ForItem(val item: Item) : Action
    data object Folder : Action
}

private enum class ItemMode { MENU, RENAME, CONFIRM_DELETE }

/**
 * A small file manager for the conversation's workspace, in the app's own style: breadcrumb
 * navigation through folders, file size and age, and per-item actions in a themed sheet (open,
 * download to the phone's Downloads folder, rename, delete). The folder menu can download the
 * whole folder as a zip, clear it, or show hidden files. Files the agent made are listed on top.
 */
@Composable
fun FileManager(
    workspace: Workspace,
    artifacts: List<FileAttachment>,
    onOpenFile: (path: String, baseSnapshot: String?) -> Unit,
    /** Bumped by the caller when the workspace may have changed (terminal command, agent step). */
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dir by rememberSaveable { mutableStateOf("/") }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<Action?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dir, showHidden, reload, refreshKey) {
        items = withContext(Dispatchers.IO) {
            runCatching {
                workspace.list(dir).filter { showHidden || !it.name.startsWith(".") }
                    .sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name.lowercase() }).map { f ->
                    Item(
                        name = f.name,
                        path = workspace.virtualOf(f),
                        dir = f.isDirectory,
                        size = if (f.isDirectory) 0 else f.length(),
                        modified = f.lastModified(),
                        children = if (f.isDirectory) f.listFiles()?.count { showHidden || !it.name.startsWith(".") } ?: 0 else 0,
                    )
                }
            }.getOrElse {
                // The folder vanished (deleted by a command): go back to the root.
                if (dir != "/") dir = "/"
                emptyList()
            }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }

    fun refresh() { reload++ }
    fun perform(label: String, block: () -> String) {
        scope.launch {
            toast = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "$label failed: ${e.message ?: e.javaClass.simpleName}"
            }
            refresh()
        }
    }
    fun download(path: String) = perform("Download") { "Saved to " + Downloads.save(context, workspace.resolve(path)) }

    val latestArtifacts = remember(artifacts) {
        artifacts.groupBy { it.path }.map { (_, v) -> v.maxBy { it.updatedAt } }.sortedByDescending { it.updatedAt }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PathBar(
                dir = dir,
                onNavigate = { dir = it },
                onUp = { dir = dir.substringBeforeLast('/', "").ifEmpty { "/" } },
                onMenu = { action = Action.Folder },
            )
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().fadeTopEdge(listState), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                if (dir == "/" && latestArtifacts.isNotEmpty()) {
                    item(key = "h:artifacts") { Label("From the agent") }
                    items(latestArtifacts.take(3), key = { "a:" + it.path }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenFile(a.path, a.baseSnapshot) }
                                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(R.drawable.ic_file_edit, null, tint = Palette.IconActive, size = 20.dp)
                            Spacer(Modifier.width(16.dp))
                            FadingText(a.name, MaterialTheme.typography.bodyLarge, Palette.TextPrimary, Modifier.weight(1f))
                            if (a.baseSnapshot != null) Text(
                                "+${a.added} \u2212${a.removed}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.TextTertiary,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            QuietIconButton(R.drawable.ic_download, "Download ${a.name}", onClick = { download(a.path) }, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
                        }
                    }
                    item(key = "h:files") { Label("All files") }
                }
                if (items.isEmpty()) item(key = "empty") {
                    Text(
                        if (dir == "/") "The workspace is empty. Files the agent or the terminal create appear here." else "This folder is empty.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextTertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
                items(items, key = { "f:" + it.path }) { item ->
                    ItemRow(
                        item = item,
                        onClick = { if (item.dir) dir = item.path else onOpenFile(item.path, null) },
                        onMore = { action = Action.ForItem(item) },
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) {
            Text(
                toast.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextPrimary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHighest)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }

    when (val a = action) {
        is Action.ForItem -> ItemSheet(
            item = a.item,
            onDismiss = { action = null },
            onOpen = { action = null; if (a.item.dir) dir = a.item.path else onOpenFile(a.item.path, null) },
            onDownload = { action = null; download(a.item.path) },
            onRename = { name ->
                action = null
                perform("Rename") {
                    val clean = name.trim().replace('/', '_')
                    val parent = a.item.path.substringBeforeLast('/', "")
                    workspace.move(a.item.path, "$parent/$clean")
                    "Renamed to $clean"
                }
            },
            onDelete = {
                action = null
                perform("Delete") { workspace.delete(a.item.path, recursive = true); "Deleted ${a.item.name}" }
            },
        )
        Action.Folder -> FolderSheet(
            dir = dir,
            count = items.size,
            showHidden = showHidden,
            onShowHidden = { showHidden = it },
            onDismiss = { action = null },
            onRefresh = { action = null; refresh() },
            onDownload = { action = null; download(dir) },
            onClear = {
                action = null
                perform("Clear") {
                    val target = workspace.resolve(dir)
                    var n = 0
                    target.listFiles()?.forEach { f ->
                        if (showHidden || !f.name.startsWith(".")) { if (f.deleteRecursively()) n++ }
                    }
                    "Removed $n item" + if (n == 1) "" else "s"
                }
            },
        )
        null -> Unit
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.TextTertiary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
}

/**
 * Breadcrumb ("workspace › notes › drafts") on the page background: no container, an Up
 * button only inside a folder, and one overflow menu for everything else.
 */
@Composable
private fun PathBar(dir: String, onNavigate: (String) -> Unit, onUp: () -> Unit, onMenu: () -> Unit) {
    val parts = dir.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = if (parts.isEmpty()) 20.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (parts.isNotEmpty()) QuietIconButton(R.drawable.ic_folder_up, "Up", onClick = onUp, tint = Palette.IconActive, size = 40.dp, iconSize = 20.dp)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crumb("workspace", current = parts.isEmpty()) { onNavigate("/") }
            parts.forEachIndexed { i, p ->
                Text("\u203a", style = MaterialTheme.typography.labelLarge, color = Palette.TextTertiary, modifier = Modifier.padding(horizontal = 2.dp))
                Crumb(p, current = i == parts.lastIndex) { onNavigate("/" + parts.take(i + 1).joinToString("/")) }
            }
        }
        QuietIconButton(R.drawable.ic_more, "Folder options", onClick = onMenu, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
    }
}

@Composable
private fun Crumb(text: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (current) Palette.TextPrimary else Palette.TextSecondary,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

@Composable
private fun ItemRow(item: Item, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            if (item.dir) R.drawable.ic_folder else iconFor(item.name), null,
            tint = if (item.dir) Palette.IconActive else Palette.IconMuted, size = 22.dp,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            FadingText(item.name, MaterialTheme.typography.bodyLarge, Palette.TextPrimary)
            Text(
                (if (item.dir) "${item.children} item" + (if (item.children == 1) "" else "s") else formatSize(item.size)) +
                    " \u00b7 " + formatAge(item.modified),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                maxLines = 1,
            )
        }
        QuietIconButton(R.drawable.ic_more, "Options for ${item.name}", onClick = onMore, tint = Palette.IconMuted, size = 40.dp, iconSize = 18.dp)
    }
}

@Composable
private fun ItemSheet(
    item: Item,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var mode by remember(item.path) { mutableStateOf(ItemMode.MENU) }
    AppSheet(onDismiss = onDismiss, title = item.name, subtitle = "/workspace" + item.path) {
        when (mode) {
            ItemMode.MENU -> {
                SheetOption(if (item.dir) R.drawable.ic_folder else R.drawable.ic_file, if (item.dir) "Open folder" else "Open", onClick = onOpen)
                SheetOption(
                    R.drawable.ic_download,
                    "Download",
                    subtitle = "To Download/${Downloads.SUBDIR}" + if (item.dir) " as ${item.name}.zip" else "",
                    onClick = onDownload,
                )
                SheetOption(R.drawable.ic_edit, "Rename", onClick = { mode = ItemMode.RENAME })
                SheetDivider()
                SheetOption(R.drawable.ic_trash, "Delete", onClick = { mode = ItemMode.CONFIRM_DELETE }, danger = true)
            }
            ItemMode.RENAME -> SheetTextField(initial = item.name, placeholder = "New name", onSubmit = onRename)
            ItemMode.CONFIRM_DELETE -> SheetConfirm(
                message = if (item.dir) "Delete ${item.name} and everything in it?" else "Delete ${item.name}?",
                confirm = "Delete",
                onConfirm = onDelete,
                onCancel = { mode = ItemMode.MENU },
            )
        }
    }
}

@Composable
private fun FolderSheet(
    dir: String,
    count: Int,
    showHidden: Boolean,
    onShowHidden: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: () -> Unit,
    onClear: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val name = if (dir == "/") "workspace" else dir.substringAfterLast('/')
    AppSheet(onDismiss = onDismiss, title = name, subtitle = "/workspace" + if (dir == "/") "" else dir) {
        if (confirm) {
            SheetConfirm(
                message = "Remove all $count item" + (if (count == 1) "" else "s") + " in $name? This cannot be undone.",
                confirm = "Clear",
                onConfirm = onClear,
                onCancel = { confirm = false },
            )
        } else {
            SheetOption(R.drawable.ic_refresh, "Refresh", onClick = onRefresh)
            SheetOption(R.drawable.ic_download, "Download folder", subtitle = "As $name.zip in Download/${Downloads.SUBDIR}", onClick = onDownload, enabled = count > 0)
            SheetSwitch(R.drawable.ic_file, "Show hidden files", checked = showHidden, onChange = onShowHidden, subtitle = "Scripts and caches that start with a dot")
            SheetDivider()
            SheetOption(R.drawable.ic_trash, "Clear folder", onClick = { confirm = true }, danger = true, enabled = count > 0)
        }
    }
}

private fun iconFor(name: String): Int = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic" -> R.drawable.ic_image
    "py", "kt", "js", "ts", "sh", "json", "html", "css", "c", "cpp", "java", "rs", "go" -> R.drawable.ic_code
    else -> R.drawable.ic_file
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun formatAge(at: Long): String {
    val age = (System.currentTimeMillis() - at).coerceAtLeast(0)
    return when {
        age < 60_000 -> "just now"
        age < 3_600_000 -> "${age / 60_000} min ago"
        age < 86_400_000 -> "${age / 3_600_000} h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(at))
    }
}
