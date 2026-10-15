package com.sebialex.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sebialex.agent.AppContainer
import com.sebialex.agent.domain.agent.AgentEvent
import com.sebialex.agent.domain.agent.ApprovalGate
import com.sebialex.agent.domain.agent.ApprovalRequest
import com.sebialex.agent.domain.agent.Resume
import com.sebialex.agent.domain.agent.TurnStop
import com.sebialex.agent.domain.model.AskRecord
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.SessionSummary
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatUiState(
    val session: Session,
    val running: Boolean = false,
    /** Assistant turn ids whose step list is expanded. */
    val expanded: Set<String> = emptySet(),
    val pendingApproval: ApprovalRequest? = null,
    /**
     * Assistant turns whose answer just arrived and should be revealed with the typewriter.
     * Held in memory only: it is never persisted, so reopening a conversation, restarting the
     * app or coming back from another screen always shows the saved text immediately.
     */
    val revealing: Set<String> = emptySet(),
    /** Messages sent while the agent was busy; each one is sent, in order, when the turn before it finishes. */
    val queued: List<QueuedMessage> = emptyList(),
    /** The agent asked a question (ask_user) and waits: its id. The input bar then answers it. */
    val askingId: String? = null,
)

/** A workspace file offered by the @-mention popup. [path] is relative to /workspace. */
data class MentionCandidate(val path: String, val file: java.io.File, val artifact: Boolean)

private const val MAX_MENTION_FILES = 300

/** A message typed and sent while an answer was still running. [task] already holds its import mentions. */
data class QueuedMessage(val id: String, val task: String)

data class ConversationsUiState(
    val items: List<SessionSummary> = emptyList(),
    val runningIds: Set<String> = emptySet(),
    val currentId: String = "",
)

/**
 * Owns all conversations. Each conversation keeps its own messages, model context, agent run,
 * approval prompt, audit log and workspace. A run keeps going in the background if you switch
 * to another conversation, and only ever writes into its own session.
 */
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    /** One running turn. [stopped] is set by the user's Stop, so the queue is not drained after it. */
    private class Run(val turnId: String) {
        lateinit var job: Job
        var approval: CompletableDeferred<Boolean>? = null
        var pending: ApprovalRequest? = null
        var stopped = false
        /** ask_user waiting for a reply: the card's id and the answer to complete. */
        var question: CompletableDeferred<String?>? = null
        var questionId: String? = null
    }

    private val cache = HashMap<String, Session>()
    private val summaries = HashMap<String, SessionSummary>()
    private val runs = HashMap<String, Run>()
    /** Messages waiting for the running turn of each conversation to finish (declared before init: publish() reads it). */
    private val queues = HashMap<String, List<QueuedMessage>>()
    private var currentId: String = newId()
    private var expanded: Set<String> = emptySet()
    private var revealing: Set<String> = emptySet()

    private val _state = MutableStateFlow(ChatUiState(Session(currentId, "", emptyList(), 0)))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** All disk writes go through one ordered queue so saves and deletes never race. */
    private val io = Channel<() -> Unit>(Channel.UNLIMITED)

    /** Imports waiting to be sent, per conversation; [pendingImports] is the current one's. */
    private val pendingBySession = HashMap<String, List<String>>()
    private val _pendingImports = MutableStateFlow<List<String>>(emptyList())
    val pendingImports: StateFlow<List<String>> = _pendingImports.asStateFlow()

    /** Unsent text per conversation, so leaving the chat for a viewer or another chat keeps it. */
    private val drafts = HashMap<String, String>()

    fun draft(sessionId: String): String = drafts[sessionId].orEmpty()

    fun setDraft(sessionId: String, text: String) {
        if (text.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = text
    }

    private val _conversations = MutableStateFlow(ConversationsUiState())
    val conversations: StateFlow<ConversationsUiState> = _conversations.asStateFlow()

    init {
        cache[currentId] = _state.value.session
        if (container.settings.memoryForNewChats()) container.settings.setConversationMemory(currentId, true)
        viewModelScope.launch(Dispatchers.IO) {
            for (op in io) runCatching { op() }
        }
        // Turning auto-approve on while an Allow/Deny card is waiting releases that card too,
        // so the setting takes effect immediately rather than from the next action.
        viewModelScope.launch {
            container.settings.state.collect { snap ->
                if (snap.autoApprove) runs.values.forEach { it.approval?.complete(true) }
            }
        }
        viewModelScope.launch {
            val (list, active) = withContext(Dispatchers.IO) {
                container.sessions.migrateLegacy()
                val list = container.sessions.list()
                val active = container.sessions.activeId()?.let { container.sessions.load(it) }
                list to active
            }
            list.forEach { summaries[it.id] = it }
            if (active != null && cache[currentId]?.turns.isNullOrEmpty()) {
                cache.remove(currentId)
                container.settings.forgetConversation(currentId)
                cache[active.id] = recovered(active)
                currentId = active.id
            }
            publish()
        }
    }

    val currentSessionId: String get() = currentId

    /** Re-reads the saved conversations after a backup was restored, so the list shows them without a restart. */
    fun reloadConversations() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { container.sessions.list() }
            list.forEach { if (it.id != currentId) cache.remove(it.id); summaries[it.id] = it }
            publish()
        }
    }

    // ---- Conversations ------------------------------------------------------------------

    /** Switches to a saved conversation. Its text is shown as saved; nothing is replayed. */
    fun open(id: String) {
        if (id == currentId) return
        viewModelScope.launch {
            val session = cache[id] ?: withContext(Dispatchers.IO) { container.sessions.load(id) }?.let(::recovered) ?: return@launch
            cache[id] = session
            dropIfEmpty(currentId)
            currentId = id
            expanded = emptySet()
            revealing = emptySet()
            io.trySend { container.sessions.setActive(id) }
            publish()
        }
    }

    /** Starts a fresh, empty conversation with its own empty workspace. */
    fun newConversation() {
        if (cache[currentId]?.turns.isNullOrEmpty()) return
        val fresh = freshSession()
        cache[fresh.id] = fresh
        currentId = fresh.id
        expanded = emptySet()
        revealing = emptySet()
        publish()
    }

    fun deleteConversation(id: String) {
        runs.remove(id)?.let { it.approval?.complete(false); it.job.cancel() }
        cache.remove(id)
        summaries.remove(id)
        pendingBySession.remove(id)
        drafts.remove(id)
        queues.remove(id)
        container.forget(id)
        if (id == currentId) {
            val fresh = freshSession()
            cache[fresh.id] = fresh
            currentId = fresh.id
            expanded = emptySet()
        }
        publish()
        io.trySend { container.sessions.delete(id) }
    }

    /** Pins or unpins a conversation. Pinned ones are listed first. */
    fun setPinned(id: String, pinned: Boolean) = updateSession(id) { it.copy(pinned = pinned) }

    /** Renames a conversation. A user title is never replaced by an automatic one. */
    fun rename(id: String, title: String) {
        val t = title.trim().take(80)
        if (t.isEmpty()) return
        updateSession(id) { it.copy(title = t, customTitle = true) }
    }

    /**
     * Changes a conversation's metadata without touching its messages or updatedAt (so pinning
     * does not reorder the list). Works for conversations that were never opened this launch.
     */
    private fun updateSession(id: String, block: (Session) -> Session) {
        viewModelScope.launch {
            val session = cache[id] ?: withContext(Dispatchers.IO) { container.sessions.load(id) } ?: return@launch
            val next = block(session)
            cache[id] = next
            summaries[id] = SessionSummary(next.id, next.title, next.updatedAt, next.turns.size, next.pinned)
            publish()
            persist(id)
        }
    }

    fun clearAll() {
        runs.values.forEach { it.approval?.complete(false); it.job.cancel() }
        runs.clear()
        cache.clear()
        summaries.clear()
        pendingBySession.clear()
        drafts.clear()
        queues.clear()
        container.forgetAll()
        val fresh = freshSession()
        cache[fresh.id] = fresh
        currentId = fresh.id
        expanded = emptySet()
        publish()
        io.trySend { container.sessions.clearAll() }
    }

    // ---- Agent turn ---------------------------------------------------------------------

    fun send(text: String) {
        val sessionId = currentId
        // Imports travel as @path mentions at the start of the message (the bubble shows them
        // as previews). A message may be only imports.
        val imports = pendingBySession[sessionId].orEmpty()
        // The agent is waiting for an answer: what you type answers its question (imports stay pending).
        if (text.isNotBlank() && answerQuestion(text.trim())) return
        val task = (imports.joinToString(" ") { "@$it" } + " " + text.trim()).trim()
        if (task.isEmpty()) return
        pendingBySession.remove(sessionId)
        // Busy: the message waits (shown with a "Queued" badge) and goes out when this turn ends.
        if (runs.containsKey(sessionId)) {
            queues[sessionId] = queues[sessionId].orEmpty() + QueuedMessage(newId(), task)
            publish()
            return
        }
        startTurn(sessionId, task)
    }

    /** Removes a queued message before it is sent. */
    fun removeQueued(id: String) {
        queues[currentId]?.let { q -> queues[currentId] = q.filterNot { it.id == id } }
        publish()
    }

    /** Sends the next queued message of [sessionId], if any, once nothing runs there. */
    private fun drainQueue(sessionId: String) {
        if (runs.containsKey(sessionId)) return
        val q = queues[sessionId].orEmpty()
        val next = q.firstOrNull() ?: return
        queues[sessionId] = q.drop(1)
        if (cache[sessionId] == null) return
        startTurn(sessionId, next.task)
    }

    /**
     * A saved conversation as loaded from disk: a turn left unfinished by a killed process is
     * closed as interrupted (and saved that way), so it never shows as running again.
     */
    private fun recovered(session: Session): Session {
        val next = Resume.markInterrupted(session, running = runs.values.map { it.turnId }.toSet(), now = System.currentTimeMillis())
        if (next !== session) io.trySend { container.sessions.save(next) }
        return next
    }

    /**
     * "Continue from where it stopped" on an interrupted answer: a fresh agent turn on the same
     * request, with the whole conversation (the interrupted turn's finished steps included) as
     * history. No tool call is replayed; the model is told what already happened.
     */
    fun continueInterrupted(turnId: String) {
        val sessionId = currentId
        if (runs.containsKey(sessionId)) return
        val s = cache[sessionId] ?: return
        if (Resume.candidate(s)?.id != turnId) return
        val task = Resume.taskFor(s, turnId) ?: return
        mutateTurn(sessionId, turnId) { it.copy(interrupted = false) }
        startTurn(sessionId, task, resume = true)
    }

    /** [resume]: continue the last interrupted answer; no new message of yours is added. */
    private fun startTurn(sessionId: String, task: String, resume: Boolean = false) {
        val now = System.currentTimeMillis()
        val user = Turn(newId(), Role.USER, task, createdAt = now)
        val assistant = Turn(newId(), Role.ASSISTANT, "", createdAt = now + 1, complete = false)
        val before = cache[sessionId] ?: return
        val history = before.turns
        cache[sessionId] = before.copy(
            title = before.title.ifBlank { titleFor(task) },
            turns = before.turns + (if (resume) listOf(assistant) else listOf(user, assistant)),
            updatedAt = now,
        )
        val modelTask = if (resume) Resume.continuationTask(task) else task
        // Sending a new message collapses every previous execution group.
        if (sessionId == currentId) {
            expanded = setOf(assistant.id)
            io.trySend { container.sessions.setActive(sessionId) }
        }
        persist(sessionId)

        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest): Boolean {
                val run = runs[sessionId] ?: return false
                val deferred = CompletableDeferred<Boolean>()
                run.approval = deferred
                run.pending = request
                publish()
                return try {
                    deferred.await()
                } finally {
                    run.approval = null
                    run.pending = null
                    publish()
                }
            }
        }
        val chatTools = object : com.sebialex.agent.domain.agent.ChatTools {
            override suspend fun ask(id: String, question: String, choices: List<String>): String? {
                val r = runs[sessionId]?.takeIf { it.turnId == assistant.id } ?: return null
                val deferred = CompletableDeferred<String?>()
                r.question = deferred
                r.questionId = id
                mutateTurn(sessionId, assistant.id) {
                    it.copy(questions = it.questions + AskRecord(id, question, choices, askedAt = System.currentTimeMillis()))
                }
                persist(sessionId)
                return try {
                    deferred.await()
                } finally {
                    r.question = null
                    r.questionId = null
                    publish()
                }
            }

            override fun todos() = cache[sessionId]?.todos.orEmpty()

            override fun setTodos(items: List<com.sebialex.agent.domain.model.TodoItem>) = updateTodos(sessionId, items)
        }
        val run = Run(assistant.id)
        run.job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                container.newOrchestrator(sessionId).run(modelTask, history, gate, chatTools).collect { event ->
                    // After Stop nothing more of this run reaches the chat, even an event already in flight.
                    if (runs[sessionId] === run) handle(sessionId, assistant.id, event)
                }
            } finally {
                finishIfIncomplete(sessionId, run)
            }
        }
        runs[sessionId] = run
        publish()
        run.job.start()
    }

    private fun handle(sessionId: String, turnId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.StepAdded -> {
                mutateTurn(sessionId, turnId) {
                    // Steps keep the real order of events (tool, think, tool, tool, think).
                    if (it.steps.any { s -> s.id == event.step.id }) it else it.copy(steps = it.steps + event.step)
                }
                // A tool starting is a checkpoint: a kill from here on leaves a trail Continue can build on.
                persist(sessionId)
            }
            // Upsert: live reasoning updates may arrive for a row whose first event was dropped.
            is AgentEvent.StepUpdated -> mutateTurn(sessionId, turnId) { t ->
                when {
                    t.steps.any { it.id == event.step.id } -> t.copy(steps = t.steps.map { if (it.id == event.step.id) event.step else it })
                    else -> t.copy(steps = t.steps + event.step)
                }
            }.also {
                // Only state a resume can build on is saved at once; the throttled live-reasoning
                // stream (RUNNING) waits for the checkpoint that follows it.
                if (Resume.durable(event.step)) persist(sessionId)
            }
            is AgentEvent.PreviewReady -> {
                mutateTurn(sessionId, turnId) { t -> t.copy(previews = t.previews.filterNot { it.path == event.preview.path } + event.preview) }
                persist(sessionId)
            }
            is AgentEvent.PlanUpdated -> {
                mutateTurn(sessionId, turnId) { it.copy(plan = event.plan) }
                persist(sessionId)
            }
            is AgentEvent.Finished -> {
                onAnswerReceived(sessionId, turnId)
                mutateTurn(sessionId, turnId) { it.copy(text = event.text, attachments = event.attachments, artifacts = event.artifacts, sources = event.sources, complete = true) }
                persist(sessionId)
                maybeWriteTitle(sessionId)
            }
        }
    }

    /** Only an answer that arrives while its conversation is on screen gets the typewriter, once. */
    private fun onAnswerReceived(sessionId: String, turnId: String) {
        if (sessionId == currentId) revealing = revealing + turnId
    }

    /** Called when the reveal finishes or its row leaves the screen. It never replays. */
    fun markRevealed(turnId: String) {
        if (turnId !in revealing) return
        revealing = revealing - turnId
        publish()
    }

    /**
     * Ends [run]'s turn: an unfinished turn is closed as stopped ([TurnStop]) and saved, and the
     * run is forgotten. Called once by Stop (immediately) and again when the job itself ends;
     * the second call finds nothing left to do, and never touches a newer run of the chat.
     */
    private fun finishIfIncomplete(sessionId: String, run: Run) {
        val turn = cache[sessionId]?.turns?.firstOrNull { it.id == run.turnId }
        if (turn != null && !turn.complete) {
            mutateTurn(sessionId, run.turnId) { TurnStop.stopped(it, System.currentTimeMillis()) }
            persist(sessionId)
        }
        if (runs[sessionId] !== run) return
        runs.remove(sessionId)
        publish()
        // The turn is over: the next queued message goes out now, unless the user stopped it.
        if (TurnStop.drainsQueue(stoppedByUser = run.stopped)) drainQueue(sessionId)
    }

    /**
     * Stop is immediate: the run is cancelled (its model stream is disconnected, see
     * cancellableIo), the turn is closed with what it has, the button flips back to send in the
     * same frame, and an answer still typing out is shown whole at once. Queued messages stay
     * queued (paused) instead of starting a new turn the user just tried to stop.
     */
    fun stop() {
        val sessionId = currentId
        endReveal(sessionId)
        val run = runs[sessionId] ?: run { publish(); return }
        run.stopped = true
        run.approval?.complete(false)
        run.question?.complete(null)
        run.job.cancel()
        // Not waiting for the job: a blocking tool may take a moment to notice the cancel.
        finishIfIncomplete(sessionId, run)
    }

    /** Ends the typewriter of every answer of [sessionId] being revealed: the full text shows at once. */
    private fun endReveal(sessionId: String) {
        val ids = cache[sessionId]?.turns?.map { it.id }?.toSet().orEmpty()
        revealing = revealing - ids
    }

    /** Sends a queued message now (a paused queue after Stop), or leaves it queued while a turn runs. */
    fun sendQueuedNow(id: String) {
        val sessionId = currentId
        if (runs.containsKey(sessionId)) return
        val q = queues[sessionId].orEmpty()
        val msg = q.firstOrNull { it.id == id } ?: return
        queues[sessionId] = q.filterNot { it.id == id }
        startTurn(sessionId, msg.task)
    }

    /**
     * Answers the agent's waiting question (a tapped choice or typed text); the reply is saved
     * on its card and returned to the agent as the tool result. False when nothing is asked.
     */
    fun answerQuestion(answer: String): Boolean {
        val sessionId = currentId
        val run = runs[sessionId] ?: return false
        val deferred = run.question ?: return false
        val id = run.questionId
        mutateTurn(sessionId, run.turnId) { t -> t.copy(questions = t.questions.map { if (it.id == id) it.copy(answer = answer) else it }) }
        persist(sessionId)
        deferred.complete(answer)
        return true
    }

    /** Replaces a conversation's todo list (the agent's todo tool, or the user ticking an entry) and saves it. */
    private fun updateTodos(sessionId: String, items: List<com.sebialex.agent.domain.model.TodoItem>) {
        val s = cache[sessionId] ?: return
        cache[sessionId] = s.copy(todos = items)
        publish()
        persist(sessionId)
    }

    /** The user ticks or unticks a todo entry in the panel. */
    fun toggleTodo(id: Int) {
        val s = cache[currentId] ?: return
        updateTodos(currentId, s.todos.map { if (it.id == id) it.copy(done = !it.done) else it })
    }

    /** Removes the entries that are done (panel action). */
    fun clearDoneTodos() {
        val s = cache[currentId] ?: return
        updateTodos(currentId, s.todos.filterNot { it.done })
    }

    fun respondToApproval(allow: Boolean) {
        runs[currentId]?.approval?.complete(allow)
    }

    // ---- Editing, copying and deleting own messages ------------------------------------------

    /** Own messages can be edited or deleted while nothing is running in this conversation. */
    fun canModifyMessages(): Boolean = !runs.containsKey(currentId)

    /**
     * Starts editing a sent message: returns its typed text (without the import mentions) and
     * puts its imports back above the input, so re-sending keeps them unless removed.
     */
    fun beginEdit(turnId: String): String? {
        val t = cache[currentId]?.turns?.firstOrNull { it.id == turnId && it.role == Role.USER } ?: return null
        val imports = com.sebialex.agent.domain.agent.Mentions.paths(t.text).filter { it.startsWith("imports/") && workspaceFile(it) != null }
        pendingBySession[currentId] = imports
        publish()
        return com.sebialex.agent.domain.agent.Mentions.strip(t.text, imports)
    }

    /** Leaves edit mode without sending; the restored imports are put away again. */
    fun cancelEdit() {
        pendingBySession.remove(currentId)
        publish()
    }

    /**
     * Replaces a sent message: the message and everything after it leave the history, then the
     * edited text is sent as a new turn (the model sees the conversation as if it had been typed
     * that way). Files the agent already wrote stay in the workspace.
     */
    fun resendEdited(turnId: String, text: String) {
        val sessionId = currentId
        if (runs.containsKey(sessionId)) return
        val imports = pendingBySession[sessionId].orEmpty()
        if (text.isBlank() && imports.isEmpty()) return
        val s = cache[sessionId] ?: return
        val idx = s.turns.indexOfFirst { it.id == turnId }
        if (idx >= 0) {
            cache[sessionId] = s.copy(turns = s.turns.take(idx), updatedAt = System.currentTimeMillis())
            revealing = revealing - s.turns.drop(idx).map { it.id }.toSet()
        }
        send(text)
    }

    /** Deletes one of your messages and the answer that followed it (up to your next message). */
    fun deleteMessage(turnId: String) {
        val sessionId = currentId
        if (runs.containsKey(sessionId)) return
        val s = cache[sessionId] ?: return
        val idx = s.turns.indexOfFirst { it.id == turnId && it.role == Role.USER }
        if (idx < 0) return
        var end = idx + 1
        while (end < s.turns.size && s.turns[end].role != Role.USER) end++
        val next = s.copy(turns = s.turns.take(idx) + s.turns.drop(end), updatedAt = System.currentTimeMillis())
        cache[sessionId] = next
        publish()
        // Saved even when it is now empty, so the deleted message does not come back on reopen.
        io.trySend { container.sessions.save(next) }
    }

    /** The full text of a message, as typed (imports as their file names), for copying. */
    fun copyText(turnId: String): String? {
        val t = cache[currentId]?.turns?.firstOrNull { it.id == turnId } ?: return null
        if (t.role != Role.USER) return t.text
        val paths = com.sebialex.agent.domain.agent.Mentions.paths(t.text).filter { it.startsWith("imports/") }
        val body = com.sebialex.agent.domain.agent.Mentions.strip(t.text, paths)
        return body.ifBlank { paths.joinToString(" ") { it.substringAfterLast('/') } }
    }

    fun toggleSteps(turnId: String) {
        expanded = if (turnId in expanded) expanded - turnId else expanded + turnId
        publish()
    }

    /**
     * Copies a user-picked document, photo or camera capture into the current conversation's
     * workspace, byte for byte (images and binaries are never re-encoded as text).
     */
    suspend fun importFile(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val path = importPath(name)
        container.runtime(currentId).workspace.writeBytes(path, bytes)
        path
    }

    /**
     * Where an import is stored: its own folder `imports/<id>/<name>`. Re-importing a file with
     * the same name never overwrites an earlier one, so older messages keep their preview and
     * what the agent saw; the file itself keeps the user's original (sanitized) name for display.
     */
    private fun importPath(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "import" }
        val ws = container.runtime(currentId).workspace
        while (true) {
            val id = java.lang.Long.toString(System.currentTimeMillis(), 36) +
                java.lang.Long.toString((Math.random() * 1296).toLong(), 36).padStart(2, '0')
            val path = "imports/$id/$safe"
            if (!ws.exists(path)) return path
        }
    }

    fun addPendingImport(path: String) {
        val list = pendingBySession[currentId].orEmpty()
        if (path !in list) pendingBySession[currentId] = list + path
        publish()
    }

    fun removePendingImport(path: String) {
        pendingBySession[currentId]?.let { pendingBySession[currentId] = it - path }
        publish()
    }

    /** The current conversation's workspace file at [path], or null when it is not an existing file. */
    /** The workspace folder a web preview is served from; null when it no longer exists. */
    fun workspaceFolder(path: String): java.io.File? =
        runCatching { container.runtime(currentId).workspace.resolve(path) }.getOrNull()?.takeIf { it.isDirectory }

    fun workspaceFile(path: String): java.io.File? =
        runCatching { container.runtime(currentId).workspace.resolve(path) }.getOrNull()?.takeIf { it.isFile }

    /** Like [importFile], streaming from [input] so large videos are copied without loading them whole. */
    suspend fun importStream(name: String, input: java.io.InputStream): String = withContext(Dispatchers.IO) {
        val path = importPath(name)
        container.runtime(currentId).workspace.writeStream(path, input)
        path
    }

    /**
     * Files of the current conversation's workspace that can be @-mentioned: artifacts and files
     * the agent wrote first (newest first), then everything else by last change. Hidden folders
     * (scripts, temp) are left out.
     */
    suspend fun mentionCandidates(): List<MentionCandidate> = withContext(Dispatchers.IO) {
        val ws = container.runtime(currentId).workspace
        val produced = cache[currentId]?.turns.orEmpty().flatMap { it.artifacts + it.attachments }.map { it.path.removePrefix("/") }.toSet()
        runCatching { ws.walk() }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.isFile }
            .map { f -> ws.virtualOf(f).removePrefix("/") to f }
            .filterNot { (p, _) -> p.split('/').any { it.startsWith(".") } }
            .map { (p, f) -> MentionCandidate(p, f, artifact = p in produced) }
            .sortedWith(compareByDescending<MentionCandidate> { it.artifact }.thenByDescending { it.file.lastModified() })
            .take(MAX_MENTION_FILES)
            .toList()
    }

    fun step(stepId: String): Step? = _state.value.session.turns.asSequence().flatMap { it.steps }.firstOrNull { it.id == stepId }

    // ---- Internals ----------------------------------------------------------------------

    private fun mutateTurn(sessionId: String, turnId: String, block: (Turn) -> Turn) {
        val s = cache[sessionId] ?: return
        cache[sessionId] = s.copy(turns = s.turns.map { if (it.id == turnId) block(it) else it }, updatedAt = System.currentTimeMillis())
        publish()
    }

    private fun dropIfEmpty(id: String) {
        if (cache[id]?.turns.isNullOrEmpty() && !runs.containsKey(id)) {
            cache.remove(id)
            // An empty chat that was never used leaves no per-conversation settings behind.
            container.settings.forgetConversation(id)
        }
    }

    private fun publish() {
        val session = cache[currentId] ?: Session(currentId, "", emptyList(), System.currentTimeMillis()).also { cache[currentId] = it }
        val run = runs[currentId]
        _state.value = ChatUiState(
            session = session,
            running = run != null && !run.stopped,
            expanded = expanded,
            pendingApproval = run?.pending,
            revealing = revealing,
            queued = queues[currentId].orEmpty(),
            askingId = run?.takeIf { !it.stopped }?.questionId,
        )
        cache.values.filter { it.turns.isNotEmpty() }.forEach { s ->
            summaries[s.id] = SessionSummary(s.id, s.title, s.updatedAt, s.turns.size, s.pinned)
        }
        _pendingImports.value = pendingBySession[currentId].orEmpty()
        // The foreground service lives exactly as long as some conversation has a run.
        container.runKeeper.update(runs.size)
        _conversations.value = ConversationsUiState(
            items = summaries.values.sortedByDescending { it.updatedAt },
            runningIds = runs.keys.toSet(),
            currentId = currentId,
        )
    }

    private fun persist(sessionId: String) {
        val snapshot = cache[sessionId] ?: return
        if (snapshot.turns.isEmpty()) return
        io.trySend { container.sessions.save(snapshot) }
    }

    /** Placeholder title until the model has written one from the conversation. */
    private fun titleFor(task: String): String {
        // Imports are @path mentions: name the chat from the words, or the file when there are none.
        val paths = com.sebialex.agent.domain.agent.Mentions.paths(task)
        val plain = com.sebialex.agent.domain.agent.Mentions.strip(task, paths)
        return com.sebialex.agent.data.llm.TitleWriter.fallback(plain.ifBlank { paths.firstOrNull()?.substringAfterLast('/') ?: task })
    }

    private val titling = HashSet<String>()

    /**
     * After an answer, lets the model name the conversation from its content (once). A title the
     * user typed is never replaced; if the model cannot write one, the placeholder stays and the
     * next answer tries again.
     */
    private fun maybeWriteTitle(sessionId: String) {
        val s = cache[sessionId] ?: return
        if (!container.settings.autoTitles()) return
        if (s.customTitle || s.generatedTitle || sessionId in titling) return
        if (s.turns.none { it.role == com.sebialex.agent.domain.model.Role.ASSISTANT && it.complete && it.text.isNotBlank() }) return
        titling += sessionId
        viewModelScope.launch {
            try {
                val title = container.titles.title(s.turns) ?: return@launch
                // Gone from the cache means it was deleted meanwhile: never bring it back.
                val current = cache[sessionId] ?: return@launch
                if (current.customTitle) return@launch
                cache[sessionId] = current.copy(title = title, generatedTitle = true)
                publish()
                persist(sessionId)
            } finally {
                titling -= sessionId
            }
        }
    }

    /** An empty conversation; memory starts as saved (on until the user explicitly saved "off"). */
    private fun freshSession(): Session {
        val s = Session(newId(), "", emptyList(), System.currentTimeMillis())
        if (container.settings.memoryForNewChats()) container.settings.setConversationMemory(s.id, true)
        return s
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)
}
