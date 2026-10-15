package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.TODO_ACTIONS
import com.sebialex.agent.domain.model.TodoItem

/**
 * The tools that live in the chat rather than on the device: ask_user (a question card that
 * pauses the loop, like the approval card) and todo (the conversation's list, attached to the
 * top of the input). They are real tool calls like any other — the model
 * never prints their JSON — but the orchestrator answers them itself with this interface,
 * which the chat implements.
 */
interface ChatTools {
    /**
     * Shows [question] (with [choices], possibly none) in the running turn and suspends until
     * the user answers by tapping a choice or typing; null when the turn ended first.
     */
    suspend fun ask(id: String, question: String, choices: List<String>): String?

    /** The conversation's todo list as it is now (the user may have ticked items meanwhile). */
    fun todos(): List<TodoItem>

    /** Replaces the conversation's todo list (saved with the conversation). */
    fun setTodos(items: List<TodoItem>)

    /** No chat attached: questions get no answer and there is no list to keep. */
    object None : ChatTools {
        override suspend fun ask(id: String, question: String, choices: List<String>): String? = null
        override fun todos(): List<TodoItem> = emptyList()
        override fun setTodos(items: List<TodoItem>) = Unit
    }
}

/** The todo tool's list logic, kept pure so it can be tested. */
object Todos {
    /** The list after an action, whether it worked, and what the model is told. */
    data class Outcome(val items: List<TodoItem>, val ok: Boolean, val message: String)

    const val MAX_ITEMS = 50

    fun apply(items: List<TodoItem>, action: String, texts: List<String>, ids: List<Int>): Outcome {
        fun done(next: List<TodoItem>, note: String) = Outcome(next, true, note + "\n" + render(next))
        fun fail(note: String) = Outcome(items, false, note + "\n" + render(items))
        val unknown = ids.filter { id -> items.none { it.id == id } }
        return when (action) {
            "add" -> {
                val fresh = texts.map { it.trim() }.filter { it.isNotEmpty() }
                if (fresh.isEmpty()) return fail("Nothing to add: pass the new entries in `items`.")
                if (items.size + fresh.size > MAX_ITEMS) return fail("The list holds at most $MAX_ITEMS entries; remove or clear done ones first.")
                var next = (items.maxOfOrNull { it.id } ?: 0) + 1
                done(items + fresh.map { TodoItem(next++, it) }, "Added ${fresh.size}.")
            }
            "done", "reopen", "remove" -> {
                if (ids.isEmpty()) return fail("Pass the entry numbers in `ids`.")
                if (unknown.isNotEmpty()) return fail("No entry ${unknown.joinToString(", ")}.")
                val next = when (action) {
                    "remove" -> items.filterNot { it.id in ids }
                    else -> items.map { if (it.id in ids) it.copy(done = action == "done") else it }
                }
                done(next, when (action) { "done" -> "Marked done."; "reopen" -> "Reopened."; else -> "Removed." })
            }
            "clear_done" -> done(items.filterNot { it.done }, "Cleared done entries.")
            "list" -> Outcome(items, true, render(items))
            else -> fail("Unknown action \"$action\". Use one of: ${TODO_ACTIONS.joinToString(", ")}.")
        }
    }

    /** The list as the model reads it: `3. [x] text`. */
    fun render(items: List<TodoItem>): String =
        if (items.isEmpty()) "The todo list is empty." else items.joinToString("\n") { "${it.id}. [${if (it.done) "x" else " "}] ${it.text}" }

    /** The first entry still open, shown as the hint of the collapsed panel. */
    fun nextOpen(items: List<TodoItem>): TodoItem? = items.firstOrNull { !it.done }
}
