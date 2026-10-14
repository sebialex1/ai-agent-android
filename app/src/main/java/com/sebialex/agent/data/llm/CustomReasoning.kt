package com.sebialex.agent.data.llm

import com.sebialex.agent.data.secure.SecureSettings

/**
 * Reasoning support of a custom (OpenAI-compatible) provider's model, in this order:
 * 1. the user's manual override in Settings > Models > Thinking support (clearly labelled);
 * 2. the provider's own metadata saved from its model list (Anthropic `capabilities.thinking`,
 *    Gemini `models.list` `thinking`, `supported_parameters` with reasoning fields);
 * 3. documented model-family rules for a few well-known hosts (OpenAI reasoning models, Gemini
 *    2.5+ thinking models, DeepSeek's reasoner);
 * 4. otherwise "unknown" (no control, and the UI points to the override), never "unsupported".
 * Nothing here makes a request, so capability is never probed with a billable call.
 */
object CustomReasoning {
    const val OVERRIDE_SUPPORTED = "supported"
    const val OVERRIDE_UNSUPPORTED = "unsupported"
    const val MANUAL = "Manual override in Settings"

    private val LEVELS = listOf("high", "medium", "low")

    /** The request shape for a provider, from its host. */
    fun wireFor(baseUrl: String): ReasoningWire = when {
        ProviderMetadata.isAnthropicHost(baseUrl) -> ReasoningWire.ANTHROPIC
        ProviderMetadata.isGeminiHost(baseUrl) -> ReasoningWire.GEMINI
        else -> ReasoningWire.OPENAI_EFFORT
    }

    /** Gemini docs: `reasoning_effort: "none"` turns thinking off for 2.5 Flash / Flash-Lite only. */
    fun geminiCanDisable(model: String) = model.lowercase().startsWith("gemini-2.5-flash")

    fun detect(appModelId: String, provider: SecureSettings.CustomProvider?, override: String?): ModelReasoning {
        if (provider == null) return ModelReasoning.unknown(appModelId, "the provider was removed")
        val model = CustomProviders.parse(appModelId)?.second ?: appModelId
        val wire = wireFor(provider.baseUrl)
        val meta = provider.modelInfo[model]
        when (override) {
            OVERRIDE_UNSUPPORTED -> return ModelReasoning.unsupported(appModelId, MANUAL)
            OVERRIDE_SUPPORTED -> return supported(appModelId, model, provider, wire, meta, MANUAL)
        }
        meta?.reasoning?.let { yes ->
            return if (yes) supported(appModelId, model, provider, wire, meta, meta.source)
            else ModelReasoning.unsupported(appModelId, meta.source)
        }
        familyRule(appModelId, model, provider, wire)?.let { return it }
        return ModelReasoning.unknown(appModelId, "${provider.name} publishes no reasoning metadata for this model")
    }

    private fun supported(
        appModelId: String,
        model: String,
        provider: SecureSettings.CustomProvider,
        wire: ReasoningWire,
        meta: ProviderModelMeta?,
        source: String,
    ): ModelReasoning = when (wire) {
        // Anthropic's OpenAI layer ignores reasoning_effort: thinking is on/off (adaptive where
        // the model has it), and the layer returns no thinking text.
        ReasoningWire.ANTHROPIC -> ModelReasoning.controlled(
            appModelId, wire, emptyList(), canDisable = meta?.canDisable ?: true, defaultOn = false,
            allowsAuto = true, surfacesText = false, source = source, adaptive = meta?.adaptive == true,
        )
        // Gemini's OpenAI layer maps reasoning_effort low/medium/high; thought summaries are not
        // returned through reasoning_effort.
        ReasoningWire.GEMINI -> geminiCanDisable(model).let { off ->
            ModelReasoning.controlled(
                appModelId, wire, LEVELS, canDisable = off, defaultOn = true, allowsAuto = true,
                surfacesText = false, source = source, offWithEffortNone = off,
            )
        }
        else -> ModelReasoning.controlled(
            appModelId, ReasoningWire.OPENAI_EFFORT, meta?.efforts?.takeIf { it.isNotEmpty() } ?: LEVELS,
            canDisable = false, defaultOn = true, allowsAuto = true,
            // OpenAI's chat completions keep reasoning hidden; other servers may stream reasoning_content.
            surfacesText = !ProviderMetadata.isOpenAiHost(provider.baseUrl), source = source,
        )
    }

    /** Documented model families on well-known hosts. Null when no rule applies. */
    private fun familyRule(appModelId: String, model: String, provider: SecureSettings.CustomProvider, wire: ReasoningWire): ModelReasoning? {
        val id = model.lowercase()
        return when {
            ProviderMetadata.isGeminiHost(provider.baseUrl) && Regex("^gemini-(2\\.5|[3-9])").containsMatchIn(id) ->
                supported(appModelId, model, provider, wire, null, "Gemini docs: 2.5 and later models think")
            ProviderMetadata.isOpenAiHost(provider.baseUrl) && Regex("^(o1|o3|o4|gpt-5)").containsMatchIn(id) && "-chat" !in id ->
                supported(appModelId, model, provider, wire, null, "OpenAI docs: reasoning model")
            ProviderMetadata.isDeepSeekHost(provider.baseUrl) && id == "deepseek-reasoner" -> ModelReasoning.controlled(
                appModelId, ReasoningWire.OPENAI_EFFORT, emptyList(), canDisable = false, defaultOn = true,
                allowsAuto = false, surfacesText = true, source = "DeepSeek docs: deepseek-reasoner always reasons",
            )
            else -> null
        }
    }
}
