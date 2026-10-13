package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.ToolCall
import com.sebialex.agent.domain.model.ToolResult
import com.sebialex.agent.domain.model.Turn

data class Observation(val call: ToolCall, val result: ToolResult)

data class BrainContext(
    val task: String,
    val history: List<Turn>,
    val observations: List<Observation>,
    val workspaceFiles: List<String>,
)

sealed interface BrainDecision {
    data class Act(val call: ToolCall) : BrainDecision
    data class Answer(val text: String) : BrainDecision
}

/** Decides the next action given the task and everything observed so far in this turn. */
interface AgentBrain {
    suspend fun next(ctx: BrainContext): BrainDecision
}

/** Uses OpenRouter when a key is configured, otherwise the deterministic scripted brain. */
class RoutingBrain(
    private val openRouterKey: () -> String?,
    private val model: () -> String,
    private val remote: (key: String, model: String) -> AgentBrain,
    private val local: AgentBrain,
) : AgentBrain {
    override suspend fun next(ctx: BrainContext): BrainDecision {
        val key = openRouterKey() ?: return local.next(ctx)
        return try {
            remote(key, model()).next(ctx)
        } catch (e: Exception) {
            BrainDecision.Answer("I couldn't reach the model (${e.message ?: e.javaClass.simpleName}). Check the OpenRouter key in settings.")
        }
    }
}
