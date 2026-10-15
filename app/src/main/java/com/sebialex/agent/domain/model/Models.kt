package com.sebialex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role { USER, ASSISTANT }

/**
 * The type of an action in the audit trail. Drives the icon shown in the step row.
 * [REASONING] holds the model's raw reasoning for the turn; one step per model call, in the order it happened.
 */
@Serializable
enum class StepKind { SEARCH, FETCH, SHELL, FILE_READ, FILE_WRITE, PERMISSION, REASONING }

@Serializable
enum class StepStatus { RUNNING, DONE, FAILED, AWAITING_APPROVAL, DENIED }

/**
 * A single executed action. [payload] is the one-line raw useful value shown in the row
 * (query, hostname, command, file name). Everything else is only visible in the audit view.
 */
@Serializable
data class Step(
    val id: String,
    val kind: StepKind,
    val payload: String,
    val tool: String,
    val input: String,
    val output: String = "",
    val url: String? = null,
    val path: String? = null,
    val status: StepStatus = StepStatus.RUNNING,
    val risk: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val exitCode: Int? = null,
    /** The action was risky ([risk]) and ran without asking because auto-approve is on. */
    val autoApproved: Boolean = false,
)

@Serializable
data class FileAttachment(
    val path: String,
    val name: String,
    /** Snapshot id of the previous version, if the agent modified an existing file. */
    val baseSnapshot: String? = null,
    val added: Int = 0,
    val removed: Int = 0,
    val updatedAt: Long,
)

@Serializable
data class SourceRef(
    val url: String,
    val host: String,
    val title: String = "",
    /** First readable words of the fetched page, shown in the source preview. */
    val snippet: String = "",
)

/** Where one step of a [Plan] stands. */
@Serializable
enum class PlanStatus { PENDING, ACTIVE, DONE, SKIPPED }

@Serializable
data class PlanStep(val text: String, val status: PlanStatus = PlanStatus.PENDING)

/**
 * The plan the agent showed for a turn (set_plan), rendered as a card in the answer. Each new
 * set_plan call replaces it, so the card always shows the latest statuses.
 */
@Serializable
data class Plan(val title: String, val steps: List<PlanStep>, val updatedAt: Long = 0)

/**
 * A question the agent asked during a turn (ask_user) and the user's reply. [answer] is null
 * while it waits; in a finished turn null means it was never answered (the turn was stopped).
 */
@Serializable
data class AskRecord(
    val id: String,
    val question: String,
    val choices: List<String> = emptyList(),
    val answer: String? = null,
    val askedAt: Long = 0,
)

/**
 * A web app the agent built and offered to preview (preview_webapp): the workspace folder it
 * is served from, the page to open in it, and a display name for the preview window.
 */
@Serializable
data class WebPreview(val path: String, val title: String, val entry: String = "index.html")

/** One entry of a conversation's todo list (the todo tool and the panel above the input). */
@Serializable
data class TodoItem(val id: Int, val text: String, val done: Boolean = false)

@Serializable
data class Turn(
    val id: String,
    val role: Role,
    val text: String,
    val steps: List<Step> = emptyList(),
    val attachments: List<FileAttachment> = emptyList(),
    /**
     * Workspace files the agent attached to its answer on purpose (attach_files), shown as
     * compact chips that open the file viewer. Only references: the files stay in the workspace.
     */
    val artifacts: List<FileAttachment> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    val createdAt: Long,
    val complete: Boolean = true,
    /**
     * The app was killed while this answer ran (see Resume): its steps are kept, and while it is
     * the last turn the chat offers to continue it. Absent in older saves (false).
     */
    val interrupted: Boolean = false,
    /** The plan card of this answer (set_plan); absent in older saves. */
    val plan: Plan? = null,
    /** Questions the agent asked in this turn (ask_user), in order, with the answers. */
    val questions: List<AskRecord> = emptyList(),
    /** Web apps offered for preview in this turn (preview_webapp), each shown as a Preview card. */
    val previews: List<WebPreview> = emptyList(),
)

/**
 * One conversation. Each session is fully independent: its own messages, model context,
 * action history (audit log) and workspace directory.
 */
@Serializable
data class Session(
    val id: String,
    val title: String,
    val turns: List<Turn>,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** Pinned conversations are listed first, in their own section. */
    val pinned: Boolean = false,
    /** The user renamed it; automatic titles never overwrite it. */
    val customTitle: Boolean = false,
    /** The title was written by the model from the conversation (not just the first words). */
    val generatedTitle: Boolean = false,
    /** This conversation's todo list, kept by the agent's todo tool (and ticked by the user). */
    val todos: List<TodoItem> = emptyList(),
)

/** Lightweight row for the conversations menu. */
data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val turnCount: Int,
    val pinned: Boolean = false,
)
