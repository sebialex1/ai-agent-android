package com.sebialex.agent.domain.model

/** A request from the brain to run a tool. */
sealed interface ToolCall {
    val callId: String

    data class WebSearch(override val callId: String, val query: String) : ToolCall
    data class WebFetch(override val callId: String, val url: String) : ToolCall
    data class Shell(override val callId: String, val command: String) : ToolCall
    data class ReadFile(override val callId: String, val path: String) : ToolCall
    data class WriteFile(override val callId: String, val path: String, val content: String) : ToolCall
}

/** Result returned to the brain after a tool call. */
data class ToolResult(
    val callId: String,
    val ok: Boolean,
    val output: String,
    val exitCode: Int? = null,
    val sources: List<SourceRef> = emptyList(),
    val attachment: FileAttachment? = null,
    val searchHits: List<SearchHit> = emptyList(),
)

data class SearchHit(val title: String, val url: String, val snippet: String)
