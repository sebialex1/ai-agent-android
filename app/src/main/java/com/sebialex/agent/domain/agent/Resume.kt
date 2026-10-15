package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Session
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn

/**
 * Picking up a run that Android killed.
 *
 * Every turn is saved the moment it starts, as an unfinished assistant turn (`complete = false`),
 * and only marked complete when its answer arrives or it is stopped. So a saved turn that is
 * still unfinished when a conversation is loaded, with no run for it in this process, was cut
 * off: the app was killed in the background. Loading closes it as [Turn.interrupted] (its
 * finished steps are kept), and the last one in a conversation offers "Continue from where it
 * stopped".
 *
 * Continuing is a fresh agent turn on the same request with the whole conversation as history,
 * including the interrupted turn's finished steps; nothing is replayed. The model is told that
 * those steps already happened, so it checks before repeating a side effect.
 */
object Resume {
    const val INTERRUPTED_TEXT = "Interrupted: the app was closed before this finished."

    /** Told to the model with the repeated request. */
    const val CONTINUE_NOTE = "\n\n[This continues your earlier answer to the same request, which was interrupted when the app " +
        "was closed. The steps it finished are listed with that answer above and really happened: do not redo side effects " +
        "that are already done (files written, downloads, installs, requests sent); check the workspace first, then finish the task.]"

    /** Closes every unfinished turn of [session] that is not [running] as interrupted. Unchanged when there is none. */
    fun markInterrupted(session: Session, running: Set<String>, now: Long): Session {
        if (session.turns.none { !it.complete && it.id !in running }) return session
        return session.copy(
            turns = session.turns.map { t ->
                if (t.complete || t.id in running) t
                else t.copy(
                    text = t.text.ifBlank { INTERRUPTED_TEXT },
                    complete = true,
                    interrupted = t.role == Role.ASSISTANT,
                    steps = t.steps.map { s ->
                        if (s.status == StepStatus.RUNNING || s.status == StepStatus.AWAITING_APPROVAL)
                            s.copy(status = StepStatus.FAILED, output = s.output.ifEmpty { "interrupted" }, finishedAt = s.finishedAt ?: now)
                        else s
                    },
                )
            },
        )
    }

    /** The turn that can be continued: only an interrupted assistant turn that is the conversation's last. */
    fun candidate(session: Session): Turn? =
        session.turns.lastOrNull()?.takeIf { it.role == Role.ASSISTANT && it.interrupted }

    /** The user's request that [turnId] was answering (the closest user message before it). */
    fun taskFor(session: Session, turnId: String): String? {
        val idx = session.turns.indexOfFirst { it.id == turnId }
        if (idx <= 0) return null
        return session.turns.subList(0, idx).lastOrNull { it.role == Role.USER }?.text?.takeIf { it.isNotBlank() }
    }

    /** What the model receives for a continued turn: the original request and [CONTINUE_NOTE]. */
    fun continuationTask(task: String): String = task + CONTINUE_NOTE

    /**
     * True when a step update is a checkpoint worth saving at once: the step reached a state a
     * later resume can build on. Live reasoning (RUNNING) streams many updates and no side
     * effect has happened yet, so it waits for the next checkpoint; a permission card waiting for
     * the user (AWAITING_APPROVAL) is saved together with the decision that follows it.
     */
    fun durable(step: Step): Boolean =
        step.status != StepStatus.RUNNING && step.status != StepStatus.AWAITING_APPROVAL
}
