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

    /**
     * Read-only look at earlier conversations ("memory"). [action] is one of [MEMORY_ACTIONS]:
     * `list_conversations` (optionally filtered by [query]), `summarize_conversation` or
     * `read_conversation` (paged from [offset], a message index). Nothing can be changed.
     */
    data class Memory(
        override val callId: String,
        val action: String,
        val conversationId: String? = null,
        val offset: Int = 0,
        val query: String? = null,
    ) : ToolCall

    /** Lists a workspace folder (sizes, kinds), optionally recursively. */
    data class ListFiles(override val callId: String, val path: String = "/", val recursive: Boolean = false) : ToolCall

    /** Searches text in workspace files (case-insensitive substring or regex), optionally under [path]. */
    data class SearchFiles(override val callId: String, val query: String, val path: String = "/", val regex: Boolean = false) : ToolCall

    /** Replaces [oldText] with [newText] in a workspace file: exactly one match unless [replaceAll]. */
    data class EditFile(
        override val callId: String,
        val path: String,
        val oldText: String,
        val newText: String,
        val replaceAll: Boolean = false,
    ) : ToolCall

    /** Moves or renames a workspace file or folder. */
    data class MoveFile(override val callId: String, val from: String, val to: String) : ToolCall

    /** Deletes a workspace file or folder (asks first). */
    data class DeleteFile(override val callId: String, val path: String) : ToolCall

    /** Downloads a URL's bytes into the workspace (any file type). */
    data class DownloadFile(override val callId: String, val url: String, val path: String? = null) : ToolCall

    /** A raw HTTP request (APIs, JSON). GET/HEAD follow the web setting; other methods ask first. */
    data class HttpRequest(
        override val callId: String,
        val method: String,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
    ) : ToolCall

    /** Current date, time, time zone and basic device facts. */
    data class DeviceInfo(override val callId: String) : ToolCall

    /** Shows a workspace image (or a few frames of a video) to the model, for vision. */
    data class ViewImage(override val callId: String, val path: String) : ToolCall

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
    /** Pictures for the model to look at (from view_image); sent right after the tool results. */
    val images: List<ImageInput> = emptyList(),
)

data class SearchHit(val title: String, val url: String, val snippet: String)

/** Actions of the conversation memory tool. */
val MEMORY_ACTIONS = listOf("list_conversations", "summarize_conversation", "read_conversation")
