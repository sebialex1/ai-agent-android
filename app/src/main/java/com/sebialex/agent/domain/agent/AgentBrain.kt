package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import com.sebialex.agent.domain.model.Turn

/**
 * One executed tool call and its real result. [batch] groups calls that the model requested
 * together in a single assistant message (parallel tool calls), so the exact exchange can be
 * replayed to the model on the next request.
 */
data class Observation(val call: ToolCall, val result: ToolResult, val batch: Int = 0)

/**
 * Reasoning returned by the model with one response. [text] is the readable reasoning;
 * [details] is the raw `reasoning_details` JSON array, replayed unchanged so providers that
 * require it (for example during tool calls) get their reasoning blocks back.
 */
data class Thought(val text: String, val details: String? = null)

data class BrainContext(
    val task: String,
    val history: List<Turn>,
    val observations: List<Observation>,
    val workspaceFiles: List<String>,
    /** Reasoning that came with each batch of tool calls, keyed by [Observation.batch]. */
    val thoughts: Map<Int, Thought> = emptyMap(),
)

sealed interface BrainDecision {
    val thought: Thought?

    /** Execute every call, in order, then ask the brain again. */
    data class Act(val calls: List<ToolCall>, override val thought: Thought? = null) : BrainDecision {
        constructor(call: ToolCall) : this(listOf(call))
    }
    data class Answer(val text: String, override val thought: Thought? = null) : BrainDecision
}

/** Decides the next action given the task and everything observed so far in this turn. */
interface AgentBrain {
    /**
     * [onReasoning] receives raw reasoning text as it streams in, delta by delta, so the UI can
     * show live thinking. It may be called from a background thread and never after return.
     */
    suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit = {}): BrainDecision
}

/**
 * How a model that is not served by OpenRouter is answered: by [brain], or, when that provider
 * is not set up (for example no API key), with [notice] and nothing executed. [label] names the
 * provider in error messages.
 */
class BrainRoute(val label: String, val brain: AgentBrain?, val notice: String = "")

/**
 * Sends each request to the provider of the selected model. [other] claims models that run
 * elsewhere (on this phone, other cloud providers); everything else goes to OpenRouter. There is
 * no offline or scripted fallback: an unconfigured provider ends the turn with a plain notice
 * and nothing is executed.
 */
class RoutingBrain(
    private val openRouterKey: () -> String?,
    private val model: () -> String,
    private val remote: (key: String, model: String) -> AgentBrain,
    private val other: (model: String) -> BrainRoute? = { null },
) : AgentBrain {
    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
        val m = model()
        other(m)?.let { route ->
            val brain = route.brain ?: return BrainDecision.Answer(route.notice)
            return guarded(route.label) { brain.next(ctx, onReasoning) }
        }
        val key = openRouterKey() ?: return BrainDecision.Answer(NO_KEY_NOTICE)
        return guarded("model request") { remote(key, m).next(ctx, onReasoning) }
    }

    private suspend fun guarded(label: String, block: suspend () -> BrainDecision): BrainDecision = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BrainDecision.Answer("The $label failed: ${e.message ?: e.javaClass.simpleName}")
    }

    companion object {
        const val NO_KEY_NOTICE = "Add an OpenRouter API key in Settings, or pick another provider or an on-device model in the model picker, to run the agent. Nothing was executed."
    }
}
