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
    data class Finished(
        val text: String,
        val attachments: List<FileAttachment>,
        val sources: List<SourceRef>,
        /** Files the agent attached to the answer (attach_files). */
        val artifacts: List<FileAttachment> = emptyList(),
    ) : AgentEvent

    /** preview_webapp: a web app is ready to preview (a card in the answer). */
    data class PreviewReady(val preview: com.sebialex.agent.domain.model.WebPreview) : AgentEvent

    /** set_plan: the answer's plan card, replaced as a whole. */
    data class PlanUpdated(val plan: com.sebialex.agent.domain.model.Plan) : AgentEvent
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

    /**
     * [chat] answers the chat tools (questions and the todo list); see [ChatTools]. set_plan,
     * ask_user and todo never reach the permission policy or the device: they only change what
     * the chat shows, and they get no step row (their card is the row).
     */
    fun run(task: String, history: List<Turn>, gate: ApprovalGate, chat: ChatTools = ChatTools.None): Flow<AgentEvent> = channelFlow {
        val observations = mutableListOf<Observation>()
        val attachments = linkedMapOf<String, FileAttachment>()
        val artifacts = linkedMapOf<String, FileAttachment>()
        val sources = linkedMapOf<String, SourceRef>()
        val thoughts = mutableMapOf<Int, Thought>()
        var executed = 0
        var batch = 0
        // Imported images: shown with the message when the user asked to look (or wrote nothing
        // else); otherwise the model is only told they exist and may call view_image itself.
        val vision = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { attachedImages(task) }
        val images = vision.images
        val modelTask = task + vision.note
        // What the workspace looked like before this turn, so attach_files can tell the files
        // produced now (downloaded, converted, generated) from old ones the user did not ask for.
        val before = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { workspaceStamps() }
        // The decision goes to the audit log only (no row in the chat), with its distinct intent.
        vision.intent?.let { intent ->
            audit.record(
                Step(
                    id = nextId(), kind = StepKind.FILE_READ, payload = "vision: ${intent.name.lowercase()}", tool = "vision_intent",
                    input = vision.paths.joinToString(", "), output = when {
                        intent.shown -> "shown with the message"
                        intent.viewImage -> "not shown; view_image allowed on demand"
                        else -> "not shown; view_image refused for this turn"
                    },
                    status = StepStatus.DONE, startedAt = now(), finishedAt = now(),
                ),
            )
        }

        while (executed < maxSteps) {
            val memory = memoryEnabled()
            val ctx = BrainContext(
                modelTask, history, observations.toList(), workspaceFiles(), thoughts.toMap(), memoryTool = memory, images = images,
                todos = chat.todos(),
            )
            val requestedAt = now()
            // Live reasoning: each model call gets its own thought step, created on the first
            // non-blank streamed delta and updated (throttled) while the model thinks. Steps are
            // appended in event order, so the turn keeps the real interleaving
            // (tool, think, tool, tool, think). Every event carries the full step, so a dropped
            // intermediate update is harmless; the final state below is always sent.
            val live = StringBuilder()
            var liveId: String? = null
            var liveSent = false
            var lastLive = 0L
            fun liveStep(): Step = Step(
                id = liveId!!, kind = StepKind.REASONING, payload = THOUGHT_LABEL, tool = "reasoning",
                input = "", output = live.toString(), status = StepStatus.RUNNING, startedAt = requestedAt,
            )
            val decision = brain.next(ctx) { delta ->
                synchronized(live) {
                    live.append(delta)
                    // Whitespace-only so far: no row yet (never an empty placeholder).
                    if (live.isBlank()) return@synchronized
                    if (liveId == null) liveId = nextId()
                    val first = !liveSent
                    val t = now()
                    if (first || t - lastLive >= LIVE_THROTTLE_MS) {
                        lastLive = t
                        liveSent = true
                        val step = liveStep()
                        trySend(if (first) AgentEvent.StepAdded(step) else AgentEvent.StepUpdated(step))
                    }
                }
            }
            val streamed = synchronized(live) { live.toString() }
            // The brain's final thought text wins over the streamed text; never both.
            val finalText = (decision.thought?.text?.takeIf { it.isNotBlank() } ?: streamed.takeIf { it.isNotBlank() })?.trim()
            if (finalText != null) {
                val step = Step(
                    id = liveId ?: nextId(), kind = StepKind.REASONING, payload = THOUGHT_LABEL, tool = "reasoning",
                    input = "", output = finalText, status = StepStatus.DONE, startedAt = requestedAt, finishedAt = now(),
                )
                send(if (liveSent) AgentEvent.StepUpdated(step) else AgentEvent.StepAdded(step))
                audit.record(step)
            }
            when (decision) {
                is BrainDecision.Answer -> {
                    send(AgentEvent.Finished(decision.text, attachments.values.toList(), sources.values.toList(), liveArtifacts(artifacts)))
                    return@channelFlow
                }
                is BrainDecision.Act -> {
                    batch++
                    decision.thought?.let { thoughts[batch] = it }
                    for (requested in decision.calls) {
                        executed++
                        var call = requested
                        if (call is ToolCall.Invalid) {
                            observations += Observation(call, ToolResult(call.callId, false, call.reason), batch)
                            continue
                        }
                        // Only an explicit "don't look" (REFUSED) blocks view_image; USE_ONLY allows it on demand.
                        val visionRefusal = vision.refusal(call)
                        if (visionRefusal != null) {
                            observations += Observation(call, ToolResult(call.callId, false, visionRefusal), batch)
                            continue
                        }
                        // Chat tools are answered here: a plan card, a question card, the todo list.
                        if (call is ToolCall.SetPlan || call is ToolCall.AskUser || call is ToolCall.Todo) {
                            val result = when (call) {
                                is ToolCall.SetPlan -> {
                                    val plan = com.sebialex.agent.domain.model.Plan(call.title, call.steps, now())
                                    send(AgentEvent.PlanUpdated(plan))
                                    ToolResult(call.callId, true, Plans.summary(plan))
                                }
                                is ToolCall.AskUser -> {
                                    val answer = chat.ask(nextId(), call.question, call.choices)
                                    if (answer != null) ToolResult(call.callId, true, "The user answered: $answer")
                                    else ToolResult(call.callId, false, "The user did not answer.")
                                }
                                is ToolCall.Todo -> {
                                    // Always from the chat's list: the user may have ticked entries meanwhile.
                                    val outcome = Todos.apply(chat.todos(), call.action, call.items, call.ids)
                                    if (outcome.ok) chat.setTodos(outcome.items)
                                    ToolResult(call.callId, outcome.ok, outcome.message)
                                }
                                else -> ToolResult(call.callId, false, "unreachable")
                            }
                            audit.record(stepFor(call).copy(status = if (result.ok) StepStatus.DONE else StepStatus.FAILED, output = result.output, finishedAt = now()))
                            observations += Observation(call, result, batch)
                            continue
                        }
                        // The tool is not advertised when memory is off; a call anyway is refused.
                        if (call is ToolCall.Memory && !memory) {
                            observations += Observation(call, ToolResult(call.callId, false, "Memory of past conversations is turned off for this conversation."), batch)
                            continue
                        }
                        // attach_files only attaches files the user plausibly wants (see AttachPolicy).
                        var attachNotes = emptyList<String>()
                        if (call is ToolCall.AttachFiles) {
                            val attach: ToolCall.AttachFiles = call
                            val checked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                attach.files.map { spec ->
                                    spec to AttachPolicy.refusal(spec.path, task, producedThisTurn(spec.path, before, attachments.keys))
                                }
                            }
                            attachNotes = checked.mapNotNull { it.second }
                            val ok = checked.filter { it.second == null }.map { it.first }
                            if (ok.isEmpty()) {
                                observations += Observation(call, ToolResult(call.callId, false, attachNotes.joinToString("\n")), batch)
                                audit.record(
                                    stepFor(call).copy(status = StepStatus.FAILED, output = attachNotes.joinToString("\n"), finishedAt = now()),
                                )
                                continue
                            }
                            call = attach.copy(files = ok)
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
                        val result = tools.execute(call).let { r ->
                            if (attachNotes.isEmpty()) r else r.copy(output = r.output + "\n" + attachNotes.joinToString("\n"))
                        }
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
                        // Artifacts come from a real attach_files call only: no other tool attaches by side effect.
                        if (call is ToolCall.AttachFiles) result.artifacts.forEach { a -> artifacts.remove(a.path); artifacts[a.path] = a }
                        if (call is ToolCall.PreviewWebapp && result.ok) result.preview?.let { send(AgentEvent.PreviewReady(it)) }
                        // Only pages that were actually fetched and read become website sources.
                        if (result.ok) result.sources.forEach { sources.putIfAbsent(it.url, it) }
                        observations += Observation(call, result, batch)
                    }
                }
            }
        }
        send(AgentEvent.Finished("I stopped after $maxSteps tool calls without finishing.", attachments.values.toList(), sources.values.toList(), liveArtifacts(artifacts)))
    }

    private companion object {
        /** The one neutral word shown on the reasoning row. */
        const val THOUGHT_LABEL = "thought"
        /** Minimum gap between live reasoning updates, so the UI is not flooded per token. */
        const val LIVE_THROTTLE_MS = 70L
        /** Most imported pictures sent with one message. */
        const val MAX_IMAGES = 4
    }

    /**
     * The imports of one message and what the user asked for them. [intent] is null when the
     * message has no image or video import. [note] is appended to the message for the model.
     */
    private inner class Vision(
        val intent: VisionIntent.Intent?,
        val paths: List<String>,
        val images: List<com.sebialex.agent.domain.model.ImageInput>,
        val note: String,
    ) {
        /** Why a view_image call must be refused under [intent], or null when it may run. */
        fun refusal(call: ToolCall): String? {
            // Stable local copy and an explicit type check: no smart cast on the property is needed.
            val current: VisionIntent.Intent = intent ?: return null
            val isViewImage = call is ToolCall.ViewImage
            if (!isViewImage || current.viewImage) return null
            return "Refused: the user asked you not to look at images in this message."
        }
    }

    /**
     * Images and videos mentioned as `@path` in [task] that exist in the workspace. When
     * [VisionIntent] says to show them, they are loaded (videos as a few frames) and a short note
     * names them; otherwise only a note goes along, saying why they are not shown.
     */
    private fun attachedImages(task: String): Vision {
        val media = Mentions.paths(task)
            .filter { Mentions.isImage(it) || com.sebialex.agent.data.workspace.ImageEncoder.isVideo(it) }
            .mapNotNull { p -> runCatching { workspace.resolve(p) }.getOrNull()?.takeIf { it.isFile }?.let { workspace.virtualOf(it) to it } }
            .take(MAX_IMAGES)
        if (media.isEmpty()) return Vision(null, emptyList(), emptyList(), "")
        val paths = media.map { it.first }
        val names = paths.joinToString(", ")
        val intent = VisionIntent.classify(task)
        when (intent) {
            VisionIntent.Intent.REFUSED -> return Vision(
                intent, paths, emptyList(),
                "\n\n[Attached: $names. Not shown to you: the user asked you not to look at it. Do not call view_image.]",
            )
            VisionIntent.Intent.USE_ONLY -> return Vision(
                intent, paths, emptyList(),
                "\n\n[Attached: $names. Not shown to you: the user only asked you to use the file. Work with it by path; " +
                    "call view_image only if the task really needs to see it.]",
            )
            else -> Unit
        }
        val images = media.flatMap { (label, file) -> com.sebialex.agent.data.workspace.ImageEncoder.load(file, label) }
        if (images.isEmpty()) return Vision(intent, paths, images, "\n\n[Attached: $names (could not be decoded as an image).]")
        return Vision(
            intent, paths, images,
            "\n\n[Attached and shown below: ${images.joinToString(", ") { it.label }}." +
                (if (intent == VisionIntent.Intent.ONLY_MEDIA) " The user sent it without any text: describe what it shows." else "") + "]",
        )
    }

    /** Attached files that still exist when the turn ends (one was deleted or moved meanwhile: dropped). */
    private fun liveArtifacts(artifacts: Map<String, FileAttachment>): List<FileAttachment> =
        artifacts.values.filter { a -> runCatching { workspace.resolve(a.path).isFile }.getOrDefault(false) }

    /** Every workspace file with its last-modified time, keyed by virtual path. */
    private fun workspaceStamps(): Map<String, Long> =
        runCatching { workspace.walk().associate { workspace.virtualOf(it) to it.lastModified() } }.getOrDefault(emptyMap())

    /**
     * True when [path] was created or changed during this turn: it did not exist before it,
     * its modification time moved, or a tool of this turn reported writing it. Also true for a
     * path that does not exist, so the tool's own error reaches the model.
     */
    private fun producedThisTurn(path: String, before: Map<String, Long>, written: Set<String>): Boolean {
        // A missing file is left to attach_files itself, which reports "no such file".
        val f = runCatching { workspace.resolve(path) }.getOrNull()?.takeIf { it.isFile } ?: return true
        val v = workspace.virtualOf(f)
        if (v in written) return true
        val old = before[v] ?: return true
        return f.lastModified() != old
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
        is ToolCall.AttachFiles -> Step(
            nextId(), StepKind.FILE_READ, "attach " + call.files.joinToString(", ") { (it.name ?: it.path).substringAfterLast('/') },
            "attach_files", args(call), path = call.files.singleOrNull()?.path, startedAt = now(),
        )
        is ToolCall.CopyFile -> Step(nextId(), StepKind.FILE_WRITE, "copy ${call.from.substringAfterLast('/')} \u2192 ${call.to}", "copy_file", args(call), path = call.to, startedAt = now())
        is ToolCall.FileInfo -> Step(nextId(), StepKind.FILE_READ, "info ${call.path.substringAfterLast('/')}", "file_info", args(call), path = call.path, startedAt = now())
        is ToolCall.ZipFiles -> Step(nextId(), StepKind.FILE_WRITE, "zip \u2192 ${call.output.substringAfterLast('/')}", "zip_files", args(call), path = call.output, startedAt = now())
        is ToolCall.UnzipFile -> Step(nextId(), StepKind.FILE_WRITE, "unzip ${call.path.substringAfterLast('/')}", "unzip_file", args(call), path = call.path, startedAt = now())
        is ToolCall.GenerateImage -> Step(nextId(), StepKind.FILE_WRITE, "image: ${call.prompt.take(80)}", "generate_image", args(call), path = call.filename, startedAt = now())
        is ToolCall.CheckTools -> Step(nextId(), StepKind.SHELL, "check " + call.tools.joinToString(", "), "check_tools", args(call), startedAt = now())
        is ToolCall.DeviceInfo -> Step(nextId(), StepKind.SHELL, "device info", "device_info", "{}", startedAt = now())
        is ToolCall.ViewImage -> Step(nextId(), StepKind.FILE_READ, "look at ${call.path.substringAfterLast('/')}", "view_image", args(call), path = call.path, startedAt = now())
        is ToolCall.PreviewWebapp -> Step(nextId(), StepKind.FILE_READ, "preview ${call.path}", "preview_webapp", args(call), startedAt = now())
        // Audit-only rows: the chat shows these as cards.
        is ToolCall.SetPlan -> Step(nextId(), StepKind.FILE_READ, "plan: ${call.title}", "set_plan", args(call), startedAt = now())
        is ToolCall.AskUser -> Step(nextId(), StepKind.PERMISSION, "ask: ${call.question.take(80)}", "ask_user", args(call), startedAt = now())
        is ToolCall.Todo -> Step(nextId(), StepKind.FILE_WRITE, "todo ${call.action}", "todo", args(call), startedAt = now())
        is ToolCall.Invalid -> Step(nextId(), StepKind.SHELL, call.name, call.name, call.arguments, startedAt = now())
    }

    private fun args(call: ToolCall) = com.sebialex.agent.data.llm.OpenRouterBrain.arguments(call)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
