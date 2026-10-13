package com.sebialex.agent.domain.agent

import com.sebialex.agent.domain.model.ImageInput
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
    /**
     * The user turned on memory of past conversations for this conversation, so the
     * `conversation_memory` tool is offered. When false the tool is not advertised at all.
     */
    val memoryTool: Boolean = false,
    /**
     * Imported images (or video frames) shown with the current message, chosen by
     * [VisionIntent]. Empty when the user did not ask to look at them.
     */
    val images: List<ImageInput> = emptyList(),
    /** The model rejected image input once in this request, so pictures are left out (with a note). */
    val imagesDropped: Boolean = false,
) {
    /** True when this request carries any picture (with the message or from view_image). */
    val hasImages: Boolean get() = images.isNotEmpty() || observations.any { it.result.images.isNotEmpty() }

    /** The same request without any picture, for models that do not accept image input. */
    fun withoutImages(): BrainContext = copy(
        images = emptyList(),
        observations = observations.map { o ->
            if (o.result.images.isEmpty()) o else o.copy(result = o.result.copy(images = emptyList()))
        },
        imagesDropped = true,
    )

    companion object {
        /** Whether a provider error says the model cannot take images (so a retry without them can work). */
        fun isImageRejection(message: String?): Boolean {
            val m = message?.lowercase() ?: return false
            return ("image" in m || "vision" in m || "multimodal" in m || "modalit" in m) &&
                ("support" in m || "not" in m || "endpoint" in m || "invalid" in m || "unsupported" in m)
        }

        /** Told to the model when its request had to be retried without pictures. */
        const val IMAGES_DROPPED_NOTE = "[The attached image could not be shown to you: the selected model does not accept image input. " +
            "Say so briefly and suggest picking a vision-capable model.]"
    }
}

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
class BrainRoute(
    val label: String,
    val brain: AgentBrain?,
    val notice: String = "",
    /**
     * Key-based providers: [provider] names the key ring and [brainFor] builds the brain for one
     * key, so a failing key (invalid, out of credit, rate limited) is rotated to the next one.
     * When set, [brain] is ignored.
     */
    val provider: String? = null,
    val brainFor: ((key: String) -> AgentBrain)? = null,
)

/**
 * Sends each request to the provider of the selected model. [other] claims models that run
 * elsewhere (on this phone, other cloud providers); everything else goes to OpenRouter. There is
 * no offline or scripted fallback: an unconfigured provider ends the turn with a plain notice
 * and nothing is executed.
 */
class RoutingBrain(
    private val keys: KeyPool,
    private val model: () -> String,
    private val remote: (key: String, model: String) -> AgentBrain,
    private val other: (model: String) -> BrainRoute? = { null },
) : AgentBrain {
    override suspend fun next(ctx: BrainContext, onReasoning: (String) -> Unit): BrainDecision {
        val m = model()
        other(m)?.let { route ->
            val factory = route.brainFor
            val provider = route.provider
            if (factory != null && provider != null) {
                val key = keys.activeKey(provider) ?: return BrainDecision.Answer(route.notice)
                return rotating(route.label, provider, key) { k -> factory(k).next(ctx, onReasoning) }
            }
            val brain = route.brain ?: return BrainDecision.Answer(route.notice)
            return guarded(route.label) { brain.next(ctx, onReasoning) }
        }
        val key = keys.activeKey(KeyPool.OPENROUTER) ?: return BrainDecision.Answer(NO_KEY_NOTICE)
        return rotating("model request", KeyPool.OPENROUTER, key) { k -> remote(k, m).next(ctx, onReasoning) }
    }

    /**
     * Runs [block] with [first]; when it fails because of the key (401/402/403/429, quota, rate
     * limit, invalid key) the next key of [provider] is tried, until one works or every key has
     * failed. Any other error ends the turn with the provider's message, as before.
     */
    private suspend fun rotating(label: String, provider: String, first: String, block: suspend (String) -> BrainDecision): BrainDecision {
        var key = first
        val tried = LinkedHashSet<String>()
        var lastError: String? = null
        while (key !in tried) {
            tried += key
            try {
                val decision = block(key)
                keys.markKeyOk(provider, key)
                return decision
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                if (!KeyPool.isKeyFailure(message)) return BrainDecision.Answer("The $label failed: $message")
                lastError = message
                key = keys.rotateKey(provider, key, message) ?: break
            }
        }
        val count = tried.size
        return BrainDecision.Answer(
            "The $label failed: $lastError" +
                if (count > 1) " (tried $count keys; add another key or check them in Settings > Models)" else "",
        )
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
