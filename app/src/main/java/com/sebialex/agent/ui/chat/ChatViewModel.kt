package com.sebialex.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sebialex.agent.AppContainer
import com.sebialex.agent.data.sample.SampleData
import com.sebialex.agent.domain.agent.AgentEvent
import com.sebialex.agent.domain.agent.ApprovalGate
import com.sebialex.agent.domain.agent.ApprovalRequest
import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatUiState(
    val session: Session,
    val running: Boolean = false,
    /** Assistant turn ids whose step list is expanded. */
    val expanded: Set<String> = emptySet(),
    val pendingApproval: ApprovalRequest? = null,
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(Session("boot", "", emptyList(), 0)))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var approval: CompletableDeferred<Boolean>? = null
    private var activeTurnId: String? = null

    init {
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) {
                container.sessions.load() ?: SampleData.seed(container.workspace).also { container.sessions.save(it) }
            }
            _state.update { it.copy(session = session) }
        }
    }

    fun send(text: String) {
        val task = text.trim()
        if (task.isEmpty() || _state.value.running) return
        val now = System.currentTimeMillis()
        val user = Turn(id(), Role.USER, task, createdAt = now)
        val assistant = Turn(id(), Role.ASSISTANT, "", createdAt = now + 1, complete = false)
        val history = _state.value.session.turns
        activeTurnId = assistant.id
        _state.update { s ->
            val title = s.session.title.ifBlank { titleFor(task) }
            s.copy(
                session = s.session.copy(title = title, turns = s.session.turns + user + assistant),
                running = true,
                // Sending a new message collapses every previous execution group.
                expanded = setOf(assistant.id),
            )
        }
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest): Boolean {
                val deferred = CompletableDeferred<Boolean>()
                approval = deferred
                _state.update { it.copy(pendingApproval = request) }
                return try {
                    deferred.await()
                } finally {
                    approval = null
                    _state.update { it.copy(pendingApproval = null) }
                }
            }
        }
        job = viewModelScope.launch {
            try {
                container.newOrchestrator().run(task, history, gate).collect { event -> handle(assistant.id, event) }
            } finally {
                finishIfIncomplete(assistant.id)
            }
        }
    }

    private fun handle(turnId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.StepAdded -> mutateTurn(turnId) { it.copy(steps = it.steps + event.step) }
            is AgentEvent.StepUpdated -> mutateTurn(turnId) { t -> t.copy(steps = t.steps.map { if (it.id == event.step.id) event.step else it }) }
            is AgentEvent.Finished -> {
                mutateTurn(turnId) { it.copy(text = event.text, attachments = event.attachments, sources = event.sources, complete = true) }
                _state.update { it.copy(running = false) }
                persist()
            }
        }
    }

    private fun finishIfIncomplete(turnId: String) {
        val turn = _state.value.session.turns.firstOrNull { it.id == turnId } ?: return
        if (!turn.complete) {
            mutateTurn(turnId) { t ->
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
            persist()
        }
        _state.update { it.copy(running = false, pendingApproval = null) }
        if (activeTurnId == turnId) activeTurnId = null
    }

    fun stop() {
        approval?.complete(false)
        job?.cancel()
    }

    fun respondToApproval(allow: Boolean) {
        approval?.complete(allow)
    }

    fun toggleSteps(turnId: String) {
        _state.update { s -> s.copy(expanded = if (turnId in s.expanded) s.expanded - turnId else s.expanded + turnId) }
    }

    fun newConversation() {
        stop()
        val old = _state.value.session
        val fresh = Session(id(), "", emptyList(), System.currentTimeMillis())
        _state.value = ChatUiState(fresh)
        viewModelScope.launch(Dispatchers.IO) {
            container.sessions.archive(old)
            container.sessions.save(fresh)
        }
    }

    fun clearAll() {
        stop()
        val fresh = Session(id(), "", emptyList(), System.currentTimeMillis())
        _state.value = ChatUiState(fresh)
        viewModelScope.launch(Dispatchers.IO) {
            container.sessions.clearAll()
            container.sessions.save(fresh)
        }
    }

    /** Copies a user-picked document into the workspace and returns its workspace path. */
    suspend fun importFile(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "import" }
        val path = "imports/$safe"
        container.workspace.write(path, bytes.toString(Charsets.UTF_8))
        path
    }

    fun step(stepId: String): Step? = _state.value.session.turns.asSequence().flatMap { it.steps }.firstOrNull { it.id == stepId }

    private fun mutateTurn(turnId: String, block: (Turn) -> Turn) {
        _state.update { s -> s.copy(session = s.session.copy(turns = s.session.turns.map { if (it.id == turnId) block(it) else it })) }
    }

    private fun persist() {
        val snapshot = _state.value.session
        viewModelScope.launch(Dispatchers.IO) { container.sessions.save(snapshot) }
    }

    private fun titleFor(task: String): String {
        val words = task.split(Regex("\\s+")).take(5).joinToString(" ")
        return if (words.length > 34) words.take(34).trimEnd() else words
    }

    private fun id() = UUID.randomUUID().toString().take(12)
}
