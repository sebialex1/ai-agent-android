package com.sebialex.agent.domain.model

/** A request from the brain to run a tool. */
sealed interface ToolCall {
    val callId: String

    data class WebSearch(override val callId: String, val query: String) : ToolCall
    data class WebFetch(override val callId: String, val url: String) : ToolCall
    data class Shell(override val callId: String, val command: String) : ToolCall
    data class ReadFile(override val callId: String, val path: String) : ToolCall
    data class WriteFile(override val callId: String, val path: String, val content: String) : ToolCall

    /**
     * Runs Python code with Termux's real Python in the workspace. [packages] are pip-installed
     * first. The code is saved as [filename] (relative to /workspace) or under /workspace/.scripts.
     */
    data class Python(
        override val callId: String,
        val code: String,
        val packages: List<String> = emptyList(),
        val filename: String? = null,
    ) : ToolCall {
        /** Workspace path the script is written to. */
        val scriptPath: String get() = filename?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.endsWith(".py")) it else "$it.py" }
            ?: ".scripts/${callId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "run" }.take(40)}.py"
        val scriptName: String get() = scriptPath.substringAfterLast('/')
    }

    /** The model asked for a tool that does not exist or sent unusable arguments. Reported back as an error. */
    data class Invalid(override val callId: String, val name: String, val arguments: String, val reason: String) : ToolCall
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
