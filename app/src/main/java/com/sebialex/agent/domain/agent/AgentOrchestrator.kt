package com.sebialex.agent.domain.agent

import com.sebialex.agent.data.web.hostOf
import com.sebialex.agent.data.workspace.Workspace
import com.sebialex.agent.domain.model.FileAttachment
import com.sebialex.agent.domain.model.SourceRef
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepKind
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import com.sebialex.agent.domain.model.Turn
import com.sebialex.agent.domain.permission.PermissionDecision
import com.sebialex.agent.domain.permission.PermissionPolicy
import com.sebialex.agent.domain.tools.ToolExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

sealed interface AgentEvent {
    data class StepAdded(val step: Step) : AgentEvent
    data class StepUpdated(val step: Step) : AgentEvent
    data class Finished(val text: String, val attachments: List<FileAttachment>, val sources: List<SourceRef>) : AgentEvent
}

data class ApprovalRequest(val stepId: String, val summary: String, val reason: String)

/** Suspends the loop until the user allows or denies a risky action. */
interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): Boolean
}

/** Persists the raw audit trail of every tool call. */
fun interface AuditSink {
    fun record(step: Step)
}

/**
 * The agent loop: ask the brain for the next action, evaluate it against the permission policy,
 * request approval when needed, execute it, record it, and repeat until the brain answers.
 */
class AgentOrchestrator(
    private val brain: AgentBrain,
    private val tools: ToolExecutor,
    private val policy: PermissionPolicy,
    private val workspace: Workspace,
    private val audit: AuditSink,
    private val maxSteps: Int = 30,
) {
    private var seq = 0L

    fun run(task: String, history: List<Turn>, gate: ApprovalGate): Flow<AgentEvent> = channelFlow {
        val observations = mutableListOf<Observation>()
        val attachments = linkedMapOf<String, FileAttachment>()
        val sources = linkedMapOf<String, SourceRef>()
        val thoughts = mutableMapOf<Int, Thought>()
        var thoughtStep: Step? = null
        var executed = 0
        var batch = 0

        while (executed < maxSteps) {
            val ctx = BrainContext(task, history, observations.toList(), workspaceFiles(), thoughts.toMap())
            val requestedAt = now()
            val decision = brain.next(ctx)
            // Reasoning becomes one quiet step at the top of the turn; later reasoning is appended.
            decision.thought?.takeIf { it.text.isNotBlank() }?.let { t ->
                val prior = thoughtStep
                val step = if (prior == null) {
                    Step(
                        id = nextId(), kind = StepKind.REASONING, payload = THOUGHT_LABEL, tool = "reasoning",
                        input = "", output = t.text, status = StepStatus.DONE, startedAt = requestedAt, finishedAt = now(),
                    )
                } else {
                    prior.copy(output = prior.output + "\n\n" + t.text, finishedAt = now())
                }
                thoughtStep = step
                send(if (prior == null) AgentEvent.StepAdded(step) else AgentEvent.StepUpdated(step))
                audit.record(step)
            }
            when (decision) {
                is BrainDecision.Answer -> {
                    send(AgentEvent.Finished(decision.text, attachments.values.toList(), sources.values.toList()))
                    return@channelFlow
                }
                is BrainDecision.Act -> {
                    batch++
                    decision.thought?.let { thoughts[batch] = it }
                    for (call in decision.calls) {
                        executed++
                        if (call is ToolCall.Invalid) {
                            observations += Observation(call, ToolResult(call.callId, false, call.reason), batch)
                            continue
                        }
                        val allowed = when (val d = policy.evaluate(call)) {
                            PermissionDecision.Allow -> true
                            is PermissionDecision.Deny -> {
                                val s = permissionStep(d.summary, d.reason.label).copy(
                                    status = StepStatus.FAILED, output = "blocked by policy: ${d.reason.label}", finishedAt = now(),
                                )
                                send(AgentEvent.StepAdded(s)); audit.record(s)
                                false
                            }
                            is PermissionDecision.Ask -> {
                                val s = permissionStep(d.summary, d.reason.label)
                                send(AgentEvent.StepAdded(s))
                                val ok = gate.request(ApprovalRequest(s.id, d.summary, d.reason.label))
                                val done = s.copy(
                                    status = if (ok) StepStatus.DONE else StepStatus.DENIED,
                                    output = if (ok) "allowed by user" else "denied by user",
                                    finishedAt = now(),
                                )
                                send(AgentEvent.StepUpdated(done)); audit.record(done)
                                ok
                            }
                        }
                        if (!allowed) {
                            observations += Observation(call, ToolResult(call.callId, false, "Permission denied. Do not retry this action."), batch)
                            continue
                        }
                        val step = stepFor(call)
                        send(AgentEvent.StepAdded(step))
                        val result = tools.execute(call)
                        val finished = step.copy(
                            status = if (result.ok) StepStatus.DONE else StepStatus.FAILED,
                            output = result.output,
                            exitCode = result.exitCode,
                            finishedAt = now(),
                            url = result.sources.firstOrNull()?.url ?: step.url,
                            path = result.attachment?.path ?: step.path,
                        )
                        send(AgentEvent.StepUpdated(finished)); audit.record(finished)
                        result.attachment?.let { a ->
                            val prior = attachments[a.path]
                            attachments[a.path] = if (prior?.baseSnapshot != null) a.copy(baseSnapshot = prior.baseSnapshot) else a
                        }
                        // Only pages that were actually fetched and read become website sources.
                        if (result.ok) result.sources.forEach { sources.putIfAbsent(it.url, it) }
                        observations += Observation(call, result, batch)
                    }
                }
            }
        }
        send(AgentEvent.Finished("I stopped after $maxSteps tool calls without finishing.", attachments.values.toList(), sources.values.toList()))
    }

    private companion object {
        /** The one neutral word shown on the reasoning row. */
        const val THOUGHT_LABEL = "thought"
    }

    private fun workspaceFiles(): List<String> =
        workspace.walk().map { workspace.virtualOf(it) }.filterNot { it.startsWith("/.") }.sorted()

    private fun nextId() = "st-${System.currentTimeMillis().toString(36)}-${seq++}"
    private fun now() = System.currentTimeMillis()

    private fun permissionStep(summary: String, reason: String) = Step(
        id = nextId(), kind = StepKind.PERMISSION, payload = summary, tool = "permission",
        input = summary, risk = reason, status = StepStatus.AWAITING_APPROVAL, startedAt = now(),
    )

    private fun stepFor(call: ToolCall): Step = when (call) {
        is ToolCall.WebSearch -> Step(nextId(), StepKind.SEARCH, call.query, "web_search", "{\"query\": \"${esc(call.query)}\"}", startedAt = now())
        is ToolCall.WebFetch -> Step(nextId(), StepKind.FETCH, hostOf(call.url), "web_fetch", "{\"url\": \"${esc(call.url)}\"}", url = call.url, startedAt = now())
        is ToolCall.Shell -> Step(nextId(), StepKind.SHELL, call.command, "shell", call.command, startedAt = now())
        is ToolCall.ReadFile -> Step(nextId(), StepKind.FILE_READ, call.path.substringAfterLast('/'), "read_file", "{\"path\": \"${esc(call.path)}\"}", path = call.path, startedAt = now())
        is ToolCall.WriteFile -> Step(
            nextId(), StepKind.FILE_WRITE, call.path.substringAfterLast('/'), "write_file",
            "{\"path\": \"${esc(call.path)}\", \"bytes\": ${call.content.length}}\n\n${call.content}", path = call.path, startedAt = now(),
        )
        is ToolCall.Invalid -> Step(nextId(), StepKind.SHELL, call.name, call.name, call.arguments, startedAt = now())
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
