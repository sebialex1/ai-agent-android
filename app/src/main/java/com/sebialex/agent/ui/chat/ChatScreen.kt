package com.sebialex.agent.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onClose: () -> Unit,
    onOpenFile: (path: String, baseSnapshot: String?) -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var openStepId by rememberSaveable { mutableStateOf<String?>(null) }
    var moreSources by remember { mutableStateOf<List<SourceRef>?>(null) }
    val listState = rememberLazyListState()

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun insert(text: String) {
        val t = input.text
        val sep = if (t.isEmpty() || t.endsWith(" ")) "" else " "
        val next = t + sep + text
        input = TextFieldValue(next, TextRange(next.length))
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { insert(it) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "import.txt"
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@launch
            val path = vm.importFile(name, bytes)
            insert("@$path ")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ink)
            .systemBarsPadding()
            .imePadding(),
    ) {
        ChatTopBar(
            title = state.session.title,
            onClose = onClose,
            onNew = { vm.newConversation(); input = TextFieldValue("") },
            onWorkspace = onOpenWorkspace,
            onSettings = onOpenSettings,
        )
        val turns = state.session.turns
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(26.dp, androidx.compose.ui.Alignment.Bottom),
        ) {
            items(turns.asReversed(), key = { it.id }) { turn ->
                if (turn.role == Role.USER) {
                    UserMessage(turn)
                } else {
                    AssistantTurn(
                        turn = turn,
                        expanded = turn.id in state.expanded,
                        pendingApproval = state.pendingApproval,
                        onToggle = { vm.toggleSteps(turn.id) },
                        onOpenStep = { openStepId = it.id },
                        onOpenFile = { f: FileAttachment -> onOpenFile(f.path, f.baseSnapshot) },
                        onOpenSource = { openUrl(it.url) },
                        onMoreSources = { moreSources = it },
                        onApproval = vm::respondToApproval,
                        reveal = turn.id in state.revealing,
                        onRevealed = { vm.markRevealed(turn.id) },
                    )
                }
            }
        }
        InputPill(
            value = input,
            onValueChange = { input = it },
            running = state.running,
            onSend = {
                vm.send(input.text)
                input = TextFieldValue("")
                scope.launch { listState.animateScrollToItem(0) }
            },
            onStop = vm::stop,
            onAttach = { picker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
            onMic = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                try {
                    speech.launch(intent)
                } catch (_: ActivityNotFoundException) {
                }
            },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp),
        )
    }

    openStepId?.let { id ->
        val step = vm.step(id)
        if (step == null) {
            openStepId = null
        } else {
            StepDetailSheet(
                step = step,
                onDismiss = { openStepId = null },
                onOpenUrl = { openUrl(it) },
                onOpenPath = { openStepId = null; onOpenFile(it, null) },
            )
        }
    }
    moreSources?.let { list ->
        SourcesSheet(list, onDismiss = { moreSources = null }, onOpen = { openUrl(it.url) })
    }
}
