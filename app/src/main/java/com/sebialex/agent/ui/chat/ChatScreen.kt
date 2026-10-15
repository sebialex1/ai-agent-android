package com.sebialex.agent.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.lazy.LazyListState
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
import com.sebialex.agent.data.llm.OpenRouterModels
import com.sebialex.agent.data.secure.SecureSettings
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.ui.components.fadeTopEdge
import com.sebialex.agent.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onClose: () -> Unit,
    onOpenFile: (path: String, baseSnapshot: String?) -> Unit,
    onOpenWorkspace: () -> Unit,
    settings: SecureSettings,
    models: OpenRouterModels,
    ollama: com.sebialex.agent.data.llm.OllamaCloud,
    store: com.sebialex.agent.data.local.ModelStore,
    engine: com.sebialex.agent.data.local.LocalEngine,
    onOpenOnDevice: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The draft belongs to its conversation: restored from the view model when the chat screen
    // comes back (file viewer, workspace, another chat), and saved with the screen's state
    // (cursor included) through the app's SaveableStateHolder, which also covers process death.
    val sessionId = state.session.id
    // Keyed by conversation: a draft saved for one chat is never restored into another.
    var input by rememberSaveable(sessionId, stateSaver = TextFieldValue.Saver, key = "draft:$sessionId") {
        mutableStateOf(vm.draft(sessionId).let { TextFieldValue(it, TextRange(it.length)) })
    }
    fun updateInput(value: TextFieldValue) {
        input = value
        vm.setDraft(sessionId, value.text)
    }
    var openStepId by rememberSaveable { mutableStateOf<String?>(null) }
    // Long-pressed own message (its actions sheet) and the message being edited, per conversation.
    var actionsFor by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable(sessionId, key = "editing:$sessionId") { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var moreSources by remember { mutableStateOf<List<SourceRef>?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var previewSource by remember { mutableStateOf<SourceRef?>(null) }
    // Imported files waiting to be sent; shown as thumbnails above the input, not as @path text.
    // Held by the view model per conversation, so opening one in the viewer keeps them.
    val pending by vm.pendingImports.collectAsStateWithLifecycle()
    // Scroll position per conversation, restored when coming back from a viewer.
    val listState = rememberSaveable(sessionId, saver = LazyListState.Saver, key = "list:$sessionId") { LazyListState() }

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
        updateInput(TextFieldValue(next, TextRange(next.length)))
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { insert(it) }
    }
    /** Copies a picked document, photo or video into the workspace and adds it to the message. */
    fun importUri(uri: Uri, fallbackName: String) {
        scope.launch {
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: resolver.getType(uri)?.let { type ->
                // Media without a display name still gets a name its type can be read from.
                when {
                    type.startsWith("video/") -> "video_${System.currentTimeMillis()}." + (type.substringAfter('/').takeIf { it in setOf("mp4", "webm", "quicktime") }?.replace("quicktime", "mov") ?: "mp4")
                    type.startsWith("image/") -> "image_${System.currentTimeMillis()}." + (type.substringAfter('/').replace("jpeg", "jpg").takeIf { it.length in 3..4 } ?: "jpg")
                    else -> null
                }
            } ?: fallbackName
            val path = runCatching {
                withContext(Dispatchers.IO) { resolver.openInputStream(uri) }?.use { vm.importStream(name, it) }
            }.getOrNull() ?: return@launch
            vm.addPendingImport(path)
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri, "import.txt")
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importUri(uri, "image_${System.currentTimeMillis()}.jpg")
    }
    // A preview-size capture needs no CAMERA permission and no FileProvider.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
            }
            vm.addPendingImport(vm.importFile("photo_${System.currentTimeMillis()}.jpg", bytes))
        }
    }
    fun openPicker(source: AttachSource) {
        try {
            when (source) {
                AttachSource.CAMERA -> camera.launch(null)
                AttachSource.PHOTOS -> photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                AttachSource.FILES -> filePicker.launch(arrayOf("*/*"))
            }
        } catch (_: ActivityNotFoundException) {
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
            onNew = { updateInput(TextFieldValue("")); vm.newConversation() },
            onWorkspace = onOpenWorkspace,
        )
        val turns = state.session.turns
        // Typing "@" opens a floating file picker above the input (over the bottom of the list).
        val mention = remember(input) { activeMention(input) }
        var candidates by remember(sessionId) { mutableStateOf<List<MentionCandidate>?>(null) }
        val mentionOpen = mention != null
        androidx.compose.runtime.LaunchedEffect(mentionOpen, sessionId, turns.size) {
            if (mentionOpen) candidates = vm.mentionCandidates()
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            // Older messages dissolve under the top bar instead of being cut off at its edge.
            modifier = Modifier.fillMaxSize().fadeTopEdge(listState, reverse = true),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(26.dp, androidx.compose.ui.Alignment.Bottom),
        ) {
            // Queued messages sit at the very bottom (reverse layout), under the running turn.
            items(state.queued.asReversed(), key = { "queued:" + it.id }) { q ->
                QueuedBubble(
                    q, resolveFile = vm::workspaceFile, onRemove = { vm.removeQueued(q.id) },
                    // Nothing runs (the user pressed Stop): the queue waits until one is sent by hand.
                    paused = !state.running,
                    onSendNow = { vm.sendQueuedNow(q.id) },
                )
            }
            items(turns.asReversed(), key = { it.id }) { turn ->
                if (turn.role == Role.USER) {
                    UserMessage(
                        turn,
                        resolveFile = vm::workspaceFile,
                        onOpenFile = { onOpenFile(it, null) },
                        onLongPress = { actionsFor = turn.id },
                    )
                } else {
                    AssistantTurn(
                        turn = turn,
                        expanded = turn.id in state.expanded,
                        pendingApproval = state.pendingApproval,
                        onToggle = { vm.toggleSteps(turn.id) },
                        onOpenStep = { openStepId = it.id },
                        onOpenFile = { f: FileAttachment -> onOpenFile(f.path, f.baseSnapshot) },
                        onOpenSource = { previewSource = it },
                        onMoreSources = { moreSources = it },
                        onApproval = vm::respondToApproval,
                        reveal = turn.id in state.revealing,
                        onRevealed = { vm.markRevealed(turn.id) },
                        resolveFile = vm::workspaceFile,
                        onContinue = if (turn.interrupted && !state.running && turn.id == turns.lastOrNull()?.id) {
                            { vm.continueInterrupted(turn.id) }
                        } else null,
                    )
                }
            }
        }
        MentionOverlay(visible = mention != null, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) {
            val m = mention ?: ActiveMention(0, 0, "")
            val all = candidates.orEmpty()
            val shown = remember(all, m.query) { filterMentions(all, m.query) }
            MentionPopup(
                items = shown,
                query = m.query,
                loading = candidates == null,
                onPick = { c -> mention?.let { updateInput(applyMention(input, it, c.path)) } },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
        }
        }
        if (pending.isNotEmpty()) {
            val items = remember(pending) { pending.mapNotNull { p -> vm.workspaceFile(p)?.let { p to it } } }
            PendingImports(
                items = items,
                onRemove = vm::removePendingImport,
                onOpen = { p -> onOpenFile(p, null) },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            )
        }
        editingId?.let { id ->
            val preview = remember(id, state.session.turns) { vm.copyText(id).orEmpty() }
            EditingBanner(
                preview = preview,
                onCancel = {
                    editingId = null
                    vm.cancelEdit()
                    updateInput(TextFieldValue(""))
                },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
            )
        }
        InputPill(
            value = input,
            onValueChange = { updateInput(it) },
            running = state.running,
            hasAttachments = pending.isNotEmpty(),
            revealing = state.revealing.isNotEmpty(),
            onSend = {
                val editing = editingId
                if (editing != null) {
                    editingId = null
                    vm.resendEdited(editing, input.text)
                } else {
                    vm.send(input.text)
                }
                updateInput(TextFieldValue(""))
                scope.launch { listState.animateScrollToItem(0) }
            },
            onStop = vm::stop,
            onAttach = { attachMenu = true },
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
    actionsFor?.let { id ->
        val text = remember(id) { vm.copyText(id) }
        if (text == null) {
            actionsFor = null
        } else {
            MessageActionsSheet(
                preview = text,
                canModify = !state.running && vm.canModifyMessages(),
                onCopy = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    actionsFor = null
                },
                onEdit = {
                    actionsFor = null
                    val body = vm.beginEdit(id)
                    if (body != null) {
                        editingId = id
                        updateInput(TextFieldValue(body, TextRange(body.length)))
                    }
                },
                onDelete = {
                    actionsFor = null
                    if (editingId == id) { editingId = null; updateInput(TextFieldValue("")) }
                    vm.deleteMessage(id)
                },
                onDismiss = { actionsFor = null },
            )
        }
    }
    if (attachMenu) {
        AttachSheet(
            settings = settings,
            models = models,
            ollama = ollama,
            store = store,
            engine = engine,
            onPick = { attachMenu = false; openPicker(it) },
            onOpenOnDevice = onOpenOnDevice,
            onDismiss = { attachMenu = false },
        )
    }
    moreSources?.let { list ->
        SourcesSheet(list, onDismiss = { moreSources = null }, onOpen = { moreSources = null; previewSource = it })
    }
    // Tapping a source never navigates straight away: preview first, then "Open website".
    previewSource?.let { src ->
        SourcePreviewSheet(
            source = src,
            onDismiss = { previewSource = null },
            onOpenWebsite = { previewSource = null; openUrl(it.url) },
        )
    }
}
