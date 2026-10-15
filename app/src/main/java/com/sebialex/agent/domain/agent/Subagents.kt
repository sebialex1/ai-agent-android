package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.Role
import com.sebialex.agent.domain.model.Step
import com.sebialex.agent.domain.model.StepStatus
import com.sebialex.agent.domain.model.SubagentRun
import com.sebialex.agent.domain.model.SubagentStatus
import com.sebialex.agent.domain.model.Turn
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the main agent's subagents (spawn_subagent) come from: the models the user allowed for
 * them, the first being the default, and a fresh orchestrator for one of those models. The
 * child orchestrator shares the conversation's workspace, tools and permission policy, but
 * keeps its own transcript: its steps live in its [SubagentRun], never in the parent turn.
 */
interface Subagents {
    fun models(): List<String>
    fun orchestrator(model: String): AgentOrchestrator
}

/**
 * Lets the chat stop one running subagent from its card. The parent turn goes on and is told
 * the user stopped it. Stopping the parent turn cancels every child anyway (structured
 * concurrency: children run inside the parent's flow).
 */
class SubagentControl {
    private val jobs = ConcurrentHashMap<String, Job>()

    internal fun register(id: String, job: Job) { jobs[id] = job }
    internal fun unregister(id: String) { jobs.remove(id) }

    /** Cancels the subagent [id]; false when it is not running. */
    fun stop(id: String): Boolean {
        val job = jobs[id] ?: return false
        job.cancel(kotlinx.coroutines.CancellationException(STOPPED_BY_USER))
        return true
    }

    fun running(): Set<String> = jobs.keys.toSet()

    internal companion object {
        const val STOPPED_BY_USER = "stopped by the user"
    }
}

/** What a subagent can read of the main conversation (main_conversation). */
class ParentLink(private val history: List<Turn>, private val task: String) {
    /** The last [count] messages of the main conversation, the current request last. */
    fun recent(count: Int): String {
        val earlier = history.filter { it.text.isNotBlank() && (it.complete || it.role == Role.USER) }
            .map { (if (it.role == Role.USER) "User" else "Main agent") to it.text }
        val all = earlier + ("User (current request)" to task)
        return all.takeLast(count.coerceIn(1, 20)).joinToString("\n\n") { (who, text) ->
            "$who:\n" + text.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "\u2026" else it }
        }
    }

    private companion object { const val MAX_CHARS = 2_000 }
}

/** Pure subagent bookkeeping, kept free of coroutines and Android so it can be tested. */
object SubagentRuns {
    /** Most subagents one turn may start, and most steps one subagent may take. */
    const val MAX_PER_TURN = 6
    const val CHILD_MAX_STEPS = 20

    /** [run] with [step] added or replaced (by id), in event order. */
    fun upsert(run: SubagentRun, step: Step): SubagentRun =
        if (run.steps.any { it.id == step.id }) run.copy(steps = run.steps.map { if (it.id == step.id) step else it })
        else run.copy(steps = run.steps + step)

    /** Steps that finished, for "N steps completed" (thinking rows are not counted). */
    fun completed(run: SubagentRun): Int = run.steps.count { it.tool != "reasoning" && it.status != StepStatus.RUNNING && it.status != StepStatus.AWAITING_APPROVAL }

    /** The newest action, for the card's second line. */
    fun latest(run: SubagentRun): Step? = run.steps.lastOrNull { it.tool != "reasoning" } ?: run.steps.lastOrNull()

    /** One short line for a step, as the card and its sheet show it: "Ran yt-dlp …", "Read notes.md". */
    fun summary(step: Step): String {
        val what = step.payload.replace(Regex("\\s+"), " ").trim()
        return when (step.kind) {
            com.sebialex.agent.domain.model.StepKind.SEARCH -> "Searched $what"
            com.sebialex.agent.domain.model.StepKind.FETCH -> "Read $what"
            com.sebialex.agent.domain.model.StepKind.SHELL -> "Ran $what"
            com.sebialex.agent.domain.model.StepKind.FILE_READ -> "Read $what"
            com.sebialex.agent.domain.model.StepKind.FILE_WRITE -> "Edited $what"
            com.sebialex.agent.domain.model.StepKind.PERMISSION -> "Needs approval: $what"
            com.sebialex.agent.domain.model.StepKind.REASONING -> "Thinking"
        }
    }

    /** "Working · 3 steps completed", "Done · 5 steps", "Stopped · 2 steps", "Failed". */
    fun statusLine(runs: List<SubagentRun>): String {
        val steps = runs.sumOf { completed(it) }
        val n = if (steps == 1) "1 step" else "$steps steps"
        return when {
            runs.any { it.status == SubagentStatus.RUNNING } -> "Working \u00b7 $n completed"
            runs.all { it.status == SubagentStatus.STOPPED } -> "Stopped \u00b7 $n"
            runs.all { it.status == SubagentStatus.FAILED } -> "Failed \u00b7 $n"
            else -> "Done \u00b7 $n"
        }
    }

    /** The card's second line: the newest step while it works, then how it ended. */
    fun detailLine(run: SubagentRun): String {
        val last = latest(run)?.let { summary(it) }
        return when (run.status) {
            SubagentStatus.RUNNING -> last ?: "Starting"
            SubagentStatus.DONE -> last?.let { "Finished \u00b7 $it" } ?: "Finished"
            SubagentStatus.STOPPED -> "Stopped"
            SubagentStatus.FAILED -> "Failed" + (run.result.takeIf { it.isNotBlank() }?.let { ": ${it.take(80)}" } ?: "")
        }
    }

    /** The parent turn's runs with [run] added or replaced. */
    fun merge(runs: List<SubagentRun>, run: SubagentRun): List<SubagentRun> =
        if (runs.any { it.id == run.id }) runs.map { if (it.id == run.id) run else it } else runs + run

    /** Closes a run that is cut short with its turn (Stop, a failure, a killed app). */
    fun closed(run: SubagentRun, status: SubagentStatus, now: Long): SubagentRun {
        if (run.status != SubagentStatus.RUNNING) return run
        return run.copy(
            status = status,
            finishedAt = now,
            steps = run.steps.map { s ->
                if (s.status == StepStatus.RUNNING || s.status == StepStatus.AWAITING_APPROVAL)
                    s.copy(status = StepStatus.FAILED, output = s.output.ifEmpty { "cancelled" }, finishedAt = now)
                else s
            },
        )
    }

    /** The tool result the main agent gets for a finished, stopped or failed subagent. */
    fun report(run: SubagentRun, files: List<String>): String = buildString {
        when (run.status) {
            SubagentStatus.DONE -> append("Subagent report (").append(run.model).append("):\n").append(run.result.trim())
            SubagentStatus.STOPPED -> append("The user stopped this subagent before it finished.")
            SubagentStatus.FAILED -> append("The subagent failed: ").append(run.result.trim())
            SubagentStatus.RUNNING -> append("The subagent did not finish.")
        }
        if (files.isNotEmpty()) append("\n\nFiles it wrote: ").append(files.joinToString(", "))
        if (run.status != SubagentStatus.DONE) {
            val done = run.steps.filter { it.tool != "reasoning" && it.status == StepStatus.DONE }
            if (done.isNotEmpty()) append("\nSteps it finished: ").append(done.takeLast(8).joinToString("; ") { "${it.tool} ${it.payload.take(80)}" })
        }
    }
}
