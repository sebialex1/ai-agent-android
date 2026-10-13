package com.sebialex.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sebialex.agent.AppContainer
import com.sebialex.agent.domain.agent.AgentEvent
import com.sebialex.agent.domain.agent.ApprovalGate
import com.sebialex.agent.domain.agent.ApprovalRequest
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
)

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

    private class Run(val job: Job) {
        var approval: CompletableDeferred<Boolean>? = null
        var pending: ApprovalRequest? = null
    }

    private val cache = HashMap<String, Session>()
    private val summaries = HashMap<String, SessionSummary>()
    private val runs = HashMap<String, Run>()
    private var currentId: String = newId()
    private var expanded: Set<String> = emptySet()
    private var revealing: Set<String> = emptySet()

    private val _state = MutableStateFlow(ChatUiState(Session(currentId, "", emptyList(), 0)))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** All disk writes go through one ordered queue so saves and deletes never race. */
    private val io = Channel<() -> Unit>(Channel.UNLIMITED)

    private val _conversations = MutableStateFlow(ConversationsUiState())
    val conversations: StateFlow<ConversationsUiState> = _conversations.asStateFlow()

    init {
        cache[currentId] = _state.value.session
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
                cache[active.id] = active
                currentId = active.id
            }
            publish()
        }
    }

    val currentSessionId: String get() = currentId

    // ---- Conversations ------------------------------------------------------------------

    /** Switches to a saved conversation. Its text is shown as saved; nothing is replayed. */
    fun open(id: String) {
        if (id == currentId) return
        viewModelScope.launch {
            val session = cache[id] ?: withContext(Dispatchers.IO) { container.sessions.load(id) } ?: return@launch
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
        val fresh = Session(newId(), "", emptyList(), System.currentTimeMillis())
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
        container.forget(id)
        if (id == currentId) {
            val fresh = Session(newId(), "", emptyList(), System.currentTimeMillis())
            cache[fresh.id] = fresh
            currentId = fresh.id
            expanded = emptySet()
        }
        publish()
        io.trySend { container.sessions.delete(id) }
    }

    fun clearAll() {
        runs.values.forEach { it.approval?.complete(false); it.job.cancel() }
        runs.clear()
        cache.clear()
        summaries.clear()
        container.forgetAll()
        val fresh = Session(newId(), "", emptyList(), System.currentTimeMillis())
        cache[fresh.id] = fresh
        currentId = fresh.id
        expanded = emptySet()
        publish()
        io.trySend { container.sessions.clearAll() }
    }

    // ---- Agent turn ---------------------------------------------------------------------

    fun send(text: String) {
        val task = text.trim()
        val sessionId = currentId
        if (task.isEmpty() || runs.containsKey(sessionId)) return
        val now = System.currentTimeMillis()
        val user = Turn(newId(), Role.USER, task, createdAt = now)
        val assistant = Turn(newId(), Role.ASSISTANT, "", createdAt = now + 1, complete = false)
        val before = cache[sessionId] ?: return
        val history = before.turns
        cache[sessionId] = before.copy(
            title = before.title.ifBlank { titleFor(task) },
            turns = before.turns + user + assistant,
            updatedAt = now,
        )
        // Sending a new message collapses every previous execution group.
        expanded = setOf(assistant.id)
        io.trySend { container.sessions.setActive(sessionId) }
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
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                container.newOrchestrator(sessionId).run(task, history, gate).collect { event -> handle(sessionId, assistant.id, event) }
            } finally {
                finishIfIncomplete(sessionId, assistant.id)
            }
        }
        runs[sessionId] = Run(job)
        publish()
        job.start()
    }

    private fun handle(sessionId: String, turnId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.StepAdded -> mutateTurn(sessionId, turnId) {
                // Reasoning always sits at the top of the turn's action list.
                if (event.step.kind == StepKind.REASONING) it.copy(steps = listOf(event.step) + it.steps)
                else it.copy(steps = it.steps + event.step)
            }
            // Upsert: live reasoning updates may arrive for a row whose first event was dropped.
            is AgentEvent.StepUpdated -> mutateTurn(sessionId, turnId) { t ->
                when {
                    t.steps.any { it.id == event.step.id } -> t.copy(steps = t.steps.map { if (it.id == event.step.id) event.step else it })
                    event.step.kind == StepKind.REASONING -> t.copy(steps = listOf(event.step) + t.steps)
                    else -> t.copy(steps = t.steps + event.step)
                }
            }
            is AgentEvent.Finished -> {
                onAnswerReceived(sessionId, turnId)
                mutateTurn(sessionId, turnId) { it.copy(text = event.text, attachments = event.attachments, sources = event.sources, complete = true) }
                persist(sessionId)
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

    private fun finishIfIncomplete(sessionId: String, turnId: String) {
        val turn = cache[sessionId]?.turns?.firstOrNull { it.id == turnId }
        if (turn != null && !turn.complete) {
            mutateTurn(sessionId, turnId) { t ->
                t.copy(
                    text = t.text.ifBlank { "Stopped." },
                    complete = true,
                    steps = t.steps.map { s ->
                        if (s.status == StepStatus.RUNNING || s.status == StepStatus.AWAITING_APPROVAL)
                            s.copy(status = StepStatus.FAILED, output = s.output.ifEmpty { "cancelled" }, finishedAt = System.currentTimeMillis())
                        else s
                    },
                )
            }
            persist(sessionId)
        }
        runs.remove(sessionId)
        publish()
    }

    fun stop() {
        runs[currentId]?.let { it.approval?.complete(false); it.job.cancel() }
    }

    fun respondToApproval(allow: Boolean) {
        runs[currentId]?.approval?.complete(allow)
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
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "import" }
        val path = "imports/$safe"
        container.runtime(currentId).workspace.writeBytes(path, bytes)
        path
    }

    fun step(stepId: String): Step? = _state.value.session.turns.asSequence().flatMap { it.steps }.firstOrNull { it.id == stepId }

    // ---- Internals ----------------------------------------------------------------------

    private fun mutateTurn(sessionId: String, turnId: String, block: (Turn) -> Turn) {
        val s = cache[sessionId] ?: return
        cache[sessionId] = s.copy(turns = s.turns.map { if (it.id == turnId) block(it) else it }, updatedAt = System.currentTimeMillis())
        publish()
    }

    private fun dropIfEmpty(id: String) {
        if (cache[id]?.turns.isNullOrEmpty() && !runs.containsKey(id)) cache.remove(id)
    }

    private fun publish() {
        val session = cache[currentId] ?: Session(currentId, "", emptyList(), System.currentTimeMillis()).also { cache[currentId] = it }
        val run = runs[currentId]
        _state.value = ChatUiState(
            session = session,
            running = run != null,
            expanded = expanded,
            pendingApproval = run?.pending,
            revealing = revealing,
        )
        cache.values.filter { it.turns.isNotEmpty() }.forEach { s ->
            summaries[s.id] = SessionSummary(s.id, s.title, s.updatedAt, s.turns.size)
        }
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

    private fun titleFor(task: String): String {
        val words = task.split(Regex("\\s+")).take(6).joinToString(" ")
        return if (words.length > 40) words.take(40).trimEnd() else words
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)
}
