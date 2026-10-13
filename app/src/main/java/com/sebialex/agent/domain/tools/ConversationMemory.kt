package com.sebialex.agent.domain.tools

/**
 * Read-only access to earlier conversations for the `conversation_memory` tool. Every answer is
 * plain text already capped to a small budget, so the model starts from short summaries and
 * only pages through full detail when it needs to.
 */
interface ConversationMemory {
    /** Recent conversations (newest first), one short line each; [query] filters by text. */
    fun list(query: String?): String

    /** A compact summary of one conversation: requests, outcome, files, sources. */
    fun summarize(id: String): String

    /** Full messages of one conversation from message [offset], one page at a time. */
    fun read(id: String, offset: Int): String
}
