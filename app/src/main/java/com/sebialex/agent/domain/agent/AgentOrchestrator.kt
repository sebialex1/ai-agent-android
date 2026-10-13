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
    /** When true, risky actions skip the approval card and run; each is still recorded as auto-approved. */
    private val autoApprove: () -> Boolean = { false },
    /** Memory of earlier conversations is on for this conversation (per-conversation toggle). */
    private val memoryEnabled: () -> Boolean = { false },
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
            val memory = memoryEnabled()
            val ctx = BrainContext(task, history, observations.toList(), workspaceFiles(), thoughts.toMap(), memoryTool = memory)
            val requestedAt = now()
            // Live reasoning: the thought row is created on the first streamed delta and updated
            // (throttled) while the model thinks. Every event carries the full step, so a dropped
            // intermediate update is harmless; the final state below is always sent.
            val committed = thoughtStep
            val live = StringBuilder()
            var liveId: String? = null
            var lastLive = 0L
            fun liveStep(): Step {
                val text = live.toString()
                val base = committed
                return if (base != null) base.copy(output = base.output + "\n\n" + text, status = StepStatus.RUNNING, finishedAt = null)
                    else Step(
                        id = liveId!!, kind = StepKind.REASONING, payload = THOUGHT_LABEL, tool = "reasoning",
                        input = "", output = text, status = StepStatus.RUNNING, startedAt = requestedAt,
                    )
            }
            val decision = brain.next(ctx) { delta ->
                synchronized(live) {
                    live.append(delta)
                    val first = liveId == null
                    if (first) liveId = committed?.id ?: nextId()
                    val t = now()
                    if (first || t - lastLive >= LIVE_THROTTLE_MS) {
                        lastLive = t
                        val step = liveStep()
                        trySend(if (first && committed == null) AgentEvent.StepAdded(step) else AgentEvent.StepUpdated(step))
                    }
                }
            }
            val streamed = synchronized(live) { live.toString() }
            // Reasoning becomes one quiet step at the top of the turn; later reasoning is appended.
            val finalText = decision.thought?.text?.takeIf { it.isNotBlank() } ?: streamed.takeIf { it.isNotBlank() }
            if (finalText != null) {
                val prior = committed
                val step = if (prior == null) {
                    Step(
                        id = liveId ?: nextId(), kind = StepKind.REASONING, payload = THOUGHT_LABEL, tool = "reasoning",
                        input = "", output = finalText, status = StepStatus.DONE, startedAt = requestedAt, finishedAt = now(),
                    )
                } else {
                    prior.copy(output = prior.output + "\n\n" + finalText, status = StepStatus.DONE, finishedAt = now())
                }
                thoughtStep = step
                send(if (prior == null && liveId == null) AgentEvent.StepAdded(step) else AgentEvent.StepUpdated(step))
                audit.record(step)
            } else if (committed != null && liveId != null) {
                // Only whitespace streamed: restore the committed thought as finished.
                send(AgentEvent.StepUpdated(committed))
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
                        // The tool is not advertised when memory is off; a call anyway is refused.
                        if (call is ToolCall.Memory && !memory) {
                            observations += Observation(call, ToolResult(call.callId, false, "Memory of past conversations is turned off for this conversation."), batch)
                            continue
                        }
                        var autoRisk: String? = null
                        val allowed = when (val d = policy.evaluate(call)) {
                            PermissionDecision.Allow -> true
                            is PermissionDecision.Deny -> {
                                val s = permissionStep(d.summary, d.reason.label).copy(
                                    status = StepStatus.FAILED, output = "blocked by policy: ${d.reason.label}", finishedAt = now(),
                                )
                                send(AgentEvent.StepAdded(s)); audit.record(s)
                                false
                            }
                            // Auto-approve: no card, no pause. The action's own step carries the mark.
                            is PermissionDecision.Ask -> if (autoApprove()) {
                                autoRisk = d.reason.label
                                true
                            } else {
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
                        val step = stepFor(call).let { s ->
                            if (autoRisk != null) s.copy(autoApproved = true, risk = autoRisk) else s
                        }
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
        /** Minimum gap between live reasoning updates, so the UI is not flooded per token. */
        const val LIVE_THROTTLE_MS = 70L
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
        // Shown like a shell row: terminal icon + "python script.py". The audit keeps the code.
        is ToolCall.Python -> Step(
            nextId(), StepKind.SHELL, "python ${call.scriptName}", "python",
            buildString {
                if (call.packages.isNotEmpty()) append("# pip install ").append(call.packages.joinToString(" ")).append("\n")
                append("# /workspace/").append(call.scriptPath.removePrefix("/workspace/").removePrefix("/")).append("\n\n")
                append(call.code)
            },
            path = "/" + call.scriptPath.removePrefix("/workspace/").removePrefix("/"), startedAt = now(),
        )
        is ToolCall.Memory -> Step(
            nextId(), StepKind.FILE_READ,
            when (call.action) {
                "list_conversations" -> "past chats" + (call.query?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                "summarize_conversation" -> "past chat summary"
                else -> "past chat, from message ${call.offset}"
            },
            "conversation_memory",
            buildString {
                append("{\"action\": \"").append(esc(call.action)).append('"')
                call.conversationId?.let { append(", \"conversation_id\": \"").append(esc(it)).append('"') }
                if (call.offset > 0) append(", \"offset\": ").append(call.offset)
                call.query?.let { append(", \"query\": \"").append(esc(it)).append('"') }
                append('}')
            },
            startedAt = now(),
        )
        is ToolCall.ListFiles -> Step(
            nextId(), StepKind.FILE_READ, if (call.path.trim() in setOf("", "/")) "workspace" else call.path.trimEnd('/').substringAfterLast('/') + "/",
            "list_files", args(call), path = null, startedAt = now(),
        )
        is ToolCall.SearchFiles -> Step(nextId(), StepKind.FILE_READ, "\u201c${call.query}\u201d in files", "search_files", args(call), startedAt = now())
        is ToolCall.EditFile -> Step(
            nextId(), StepKind.FILE_WRITE, call.path.substringAfterLast('/'), "edit_file",
            "{\"path\": \"${esc(call.path)}\"}\n\n--- old\n${call.oldText}\n+++ new\n${call.newText}", path = call.path, startedAt = now(),
        )
        is ToolCall.MoveFile -> Step(nextId(), StepKind.FILE_WRITE, "${call.from.substringAfterLast('/')} \u2192 ${call.to}", "move_file", args(call), path = call.to, startedAt = now())
        is ToolCall.DeleteFile -> Step(nextId(), StepKind.FILE_WRITE, "delete ${call.path.substringAfterLast('/')}", "delete_file", args(call), startedAt = now())
        is ToolCall.DownloadFile -> Step(nextId(), StepKind.FETCH, hostOf(call.url), "download_file", args(call), url = call.url, path = call.path, startedAt = now())
        is ToolCall.HttpRequest -> Step(nextId(), StepKind.FETCH, hostOf(call.url), "http_request", "${call.method} ${call.url}" + (call.body?.let { "\n\n$it" } ?: ""), url = call.url, startedAt = now())
        is ToolCall.DeviceInfo -> Step(nextId(), StepKind.SHELL, "device info", "device_info", "{}", startedAt = now())
        is ToolCall.Invalid -> Step(nextId(), StepKind.SHELL, call.name, call.name, call.arguments, startedAt = now())
    }

    private fun args(call: ToolCall) = com.sebialex.agent.data.llm.OpenRouterBrain.arguments(call)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
