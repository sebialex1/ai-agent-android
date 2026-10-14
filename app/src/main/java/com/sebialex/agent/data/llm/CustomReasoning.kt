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

    /**
     * Gemini OpenAI compatibility docs: `reasoning_effort: "none"` turns thinking off "for 2.5
     * models"; "Reasoning cannot be turned off for Gemini 2.5 Pro or 3 models". So 2.5 Flash and
     * Flash-Lite only.
     */
    fun geminiCanDisable(model: String) = model.lowercase().startsWith("gemini-2.5-flash")

    /**
     * Effort levels for a Gemini model through `reasoning_effort` (low/medium/high map to
     * `thinking_level` / `thinking_budget`). Gemini 3 Pro Preview supports only the low and high
     * thinking levels (Gemini thinking docs), so medium is not offered there.
     */
    fun geminiLevels(model: String): List<String> =
        if (Regex("^gemini-3-pro").containsMatchIn(model.lowercase())) listOf("high", "low") else LEVELS

    /** Gemini thinking docs: 2.5 Flash-Lite does not think by default; the other thinking models do. */
    fun geminiDefaultOn(model: String) = !model.lowercase().startsWith("gemini-2.5-flash-lite")

    /**
     * Whether a Claude model takes adaptive thinking (`thinking: {type: "adaptive"}`) rather than
     * manual extended thinking (`{type: "enabled", budget_tokens}`), from Anthropic's per-model
     * table when no `/v1/models` capabilities were saved: Claude 4.5 and earlier support only
     * extended thinking and reject "adaptive"; 4.6 accepts both (extended deprecated); 4.7 and
     * later reject "enabled" with a 400. Ids that carry no version (new families) are treated as
     * current models, which use adaptive thinking.
     */
    fun claudeUsesAdaptive(model: String): Boolean {
        val m = Regex("^claude-(?:[a-z]+-)?(\\d+)(?:-(\\d{1,2}))?(?=-|$)").find(model.lowercase()) ?: return true
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        return major > 4 || (major == 4 && minor >= 6)
    }

    /**
     * What the thinking control can actually do through a provider's OpenAI-compatible endpoint,
     * shown in Settings so the UI never claims a control the endpoint ignores. Null for providers
     * with nothing specific to say.
     */
    fun compatibilityNote(baseUrl: String): String? = when (wireFor(baseUrl)) {
        ReasoningWire.ANTHROPIC ->
            "Anthropic's OpenAI-compatible endpoint ignores reasoning_effort, so Claude gets thinking on or off only, " +
                "Off only where its capabilities allow it, and no thinking text comes back."
        ReasoningWire.GEMINI ->
            "Gemini's OpenAI-compatible endpoint takes reasoning_effort Low/Medium/High (Low/High on Gemini 3 Pro), " +
                "Off only on 2.5 Flash and Flash-Lite, and returns no thought text with it."
        else -> null
    }

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
        // Anthropic's OpenAI layer: `reasoning_effort` is "Ignored" (its field table), so no effort
        // levels; the `thinking` parameter is passed on, and "the OpenAI SDK doesn't return Claude's
        // thinking". Off is offered only where /v1/models says `thinking.types.disabled` is
        // supported: several current models reject "disabled" with a 400 (always-on thinking).
        // Adaptive vs manual comes from the capabilities, else Anthropic's per-model table.
        ReasoningWire.ANTHROPIC -> ModelReasoning.controlled(
            appModelId, wire, emptyList(), canDisable = meta?.canDisable == true, defaultOn = false,
            allowsAuto = true, surfacesText = false, source = source,
            adaptive = meta?.adaptive ?: claudeUsesAdaptive(model),
        )
        // Gemini's OpenAI layer maps reasoning_effort low/medium/high; thought summaries need
        // extra_body thinking_config, which cannot be combined with reasoning_effort, so no text.
        ReasoningWire.GEMINI -> geminiCanDisable(model).let { off ->
            ModelReasoning.controlled(
                appModelId, wire, geminiLevels(model), canDisable = off, defaultOn = geminiDefaultOn(model), allowsAuto = true,
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
