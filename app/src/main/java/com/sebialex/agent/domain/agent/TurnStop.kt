package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.Turn

/**
 * What ending a turn early does to it, kept free of Android so it can be tested.
 *
 * A turn ends either because its answer arrived ([Turn.complete] is already true and nothing
 * changes) or because it was cut short: the user pressed Stop, the run failed, or the
 * conversation was left mid-run. A cut-short turn keeps whatever it already has; steps still in
 * flight are closed as cancelled, an empty answer reads "Stopped.", and the turn is complete so
 * it never shows as running again.
 */
object TurnStop {
    const val STOPPED_TEXT = "Stopped."

    fun stopped(turn: Turn, now: Long): Turn {
        if (turn.complete) return turn
        return turn.copy(
            text = turn.text.ifBlank { STOPPED_TEXT },
            complete = true,
            steps = turn.steps.map { s ->
                if (s.status == StepStatus.RUNNING || s.status == StepStatus.AWAITING_APPROVAL)
                    s.copy(status = StepStatus.FAILED, output = s.output.ifEmpty { "cancelled" }, finishedAt = now)
                else s
            },
            // Its subagents were cancelled with it.
            subagents = turn.subagents.map { SubagentRuns.closed(it, com.sebialex.agent.domain.model.SubagentStatus.STOPPED, now) },
        )
    }

    /**
     * Whether the next queued message goes out when a turn ends. After the user's own Stop it
     * does not: Stop means "stop talking", so the queue waits (shown as paused) until the user
     * sends one of them or a new message.
     */
    fun drainsQueue(stoppedByUser: Boolean): Boolean = !stoppedByUser
}
