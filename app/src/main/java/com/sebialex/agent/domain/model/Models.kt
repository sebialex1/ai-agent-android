package com.sebialex.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role { USER, ASSISTANT }

/**
 * The type of an action in the audit trail. Drives the icon shown in the step row.
 * [REASONING] holds the model's raw reasoning for the turn; it is always the first step.
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
)

/** Lightweight row for the conversations menu. */
data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val turnCount: Int,
    val pinned: Boolean = false,
)
