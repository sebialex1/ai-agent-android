package com.sebialex.agent.ui.terminal

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sebialex.agent.R
import com.sebialex.agent.data.shell.Shell
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

/**
 * User-controlled workspace shell. Same sandbox and permission policy the agent uses;
 * risky commands need an explicit confirmation before they run.
 */
@Composable
fun WorkspaceScreen(
    shell: Shell,
    policy: PermissionPolicy,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<Entry>() }
    var cwd by rememberSaveable { mutableStateOf("/") }
    var input by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()

    fun run(command: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { shell.execute(command, cwd) }
            if (r.output == "\u000c") entries.clear() else entries += Entry(cwd, command, r.output, r.exitCode)
            cwd = r.cwd
            r.openFile?.let(onOpenFile)
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

    LaunchedEffect(Unit) { if (entries.isEmpty()) run("ls") }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietIconButton(R.drawable.ic_back, "Back", onClick = onBack)
            Text(
                "/workspace" + if (cwd == "/") "" else cwd,
                style = MonoSmall,
                color = Palette.TextSecondary,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                maxLines = 1,
            )
            QuietIconButton(R.drawable.ic_folder, "List files", onClick = { run("tree") }, tint = Palette.IconMuted)
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries) { e ->
                    Column(Modifier.padding(bottom = 14.dp)) {
                        Text("$ ${e.command}", style = MonoSmall, color = Palette.TextPrimary)
                        if (e.output.isNotEmpty()) {
                            Text(e.output, style = MonoSmall, color = if (e.code == 0) Palette.TextSecondary else Palette.Danger, modifier = Modifier.padding(top = 4.dp))
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
                    PillButton("Cancel", primary = false) { pending = null }
                    Spacer(Modifier.width(8.dp))
                    PillButton("Run", primary = true) { pending = null; run(command) }
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
            AppIcon(R.drawable.ic_terminal, null, size = 18.dp)
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = MonoSmall.copy(color = Palette.TextPrimary),
                cursorBrush = SolidColor(Palette.TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            )
            QuietIconButton(R.drawable.ic_arrow_up, "Run", onClick = { submit() }, enabled = input.isNotBlank())
        }
    }
}
