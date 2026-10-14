package com.sebialex.agent

import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.llm.ReasoningPreference
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.llm.ReasoningSupport
import com.sebialex.agent.data.llm.ReasoningWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningRulesTest {
    private val auto = ReasoningPreference(null, null)
    private val off = ReasoningPreference(false, null)
    private val on = ReasoningPreference(true, null)
    private fun level(e: String) = ReasoningPreference(true, e)
    private fun fields(info: ModelReasoning, pref: ReasoningPreference) = ReasoningRules.requestFields(info, pref).toString()

    private fun openRouter(entry: String) = ModelReasoning.parse(Json.parseToJsonElement(entry).jsonObject)

    @Test fun openRouterKeepsItsReasoningObject() {
        val m = openRouter("""{"id":"a/b","supported_parameters":["reasoning","include_reasoning"],
            "reasoning":{"supported_efforts":["high","medium","low","none"],"default_enabled":false}}""")
        assertEquals(ReasoningWire.OPENROUTER, m.wire)
        assertEquals("""{"reasoning":{"effort":"none"}}""", fields(m, auto))
        assertEquals("""{"reasoning":{"effort":"medium"}}""", fields(m, level("medium")))
        assertEquals("""{"reasoning":{"enabled":true}}""", fields(m, on))
        assertEquals(listOf("off", "high", "medium", "low"), ReasoningRules.choices(m).map { it.id })
        // No Auto on OpenRouter: an unset choice resolves to the model default, sent explicitly.
        assertEquals("off", ReasoningRules.selected(m, auto))
    }

    @Test fun openRouterReasoningEffortOnly() {
        val m = openRouter("""{"id":"x","supported_parameters":["reasoning_effort","include_reasoning"],"reasoning":{"supported_efforts":["high","low"],"default_enabled":true,"default_effort":"high"}}""")
        assertEquals("""{"reasoning_effort":"high","include_reasoning":true}""", fields(m, auto))
        assertEquals("""{"reasoning_effort":"none"}""", fields(m, off))
    }

    @Test fun anthropicUsesThinkingBlocks() {
        val adaptive = ModelReasoning.controlled("custom/an/claude", ReasoningWire.ANTHROPIC, emptyList(), canDisable = true, defaultOn = false,
            allowsAuto = true, surfacesText = false, source = "t", adaptive = true)
        assertEquals("{}", fields(adaptive, auto))
        assertEquals("""{"thinking":{"type":"adaptive"}}""", fields(adaptive, on))
        assertEquals("""{"thinking":{"type":"disabled"}}""", fields(adaptive, off))
        assertEquals(listOf("auto", "off", "on"), ReasoningRules.choices(adaptive).map { it.id })
        val manual = adaptive.copy(adaptive = false)
        assertEquals(
            """{"thinking":{"type":"enabled","budget_tokens":${ReasoningRules.ANTHROPIC_BUDGET}},"max_tokens":${ReasoningRules.ANTHROPIC_MAX_TOKENS}}""",
            fields(manual, on),
        )
        // Reasoning capability is separate from whether the text comes back.
        assertEquals(ReasoningSupport.SUPPORTED, adaptive.support)
        assertEquals(false, adaptive.surfacesText)
    }

    @Test fun geminiUsesReasoningEffortAndNoneOnlyWhereAllowed() {
        val flash25 = ModelReasoning.controlled("g", ReasoningWire.GEMINI, listOf("high", "medium", "low"), canDisable = true, defaultOn = true,
            allowsAuto = true, surfacesText = false, source = "t", offWithEffortNone = true)
        assertEquals("{}", fields(flash25, auto))
        assertEquals("""{"reasoning_effort":"low"}""", fields(flash25, level("low")))
        assertEquals("""{"reasoning_effort":"none"}""", fields(flash25, off))
        assertEquals(listOf("auto", "off", "high", "medium", "low"), ReasoningRules.choices(flash25).map { it.id })
        val pro = ModelReasoning.controlled("g", ReasoningWire.GEMINI, listOf("high", "medium", "low"), canDisable = false, defaultOn = true,
            allowsAuto = true, surfacesText = false, source = "t")
        assertEquals(listOf("auto", "high", "medium", "low"), ReasoningRules.choices(pro).map { it.id })
        // "Off" cannot be stored for a model that cannot be switched off: it resolves to on.
        assertEquals("{}", fields(pro, off))
        assertTrue(ReasoningRules.resolve(pro, off).on)
    }

    @Test fun openAiEffortLevels() {
        val m = ModelReasoning.controlled("o", ReasoningWire.OPENAI_EFFORT, listOf("high", "medium", "low"), canDisable = false, defaultOn = true,
            allowsAuto = true, surfacesText = false, source = "t")
        assertEquals("{}", fields(m, auto))
        assertEquals("""{"reasoning_effort":"high"}""", fields(m, level("high")))
        // A level that is not offered is never sent.
        assertEquals("{}", fields(m, level("xhigh")))
        assertEquals("auto", ReasoningRules.selected(m, auto))
        assertEquals("high", ReasoningRules.selected(m, level("high")))
    }

    @Test fun ollamaBooleanAndLevels() {
        val bool = ModelReasoning("ollama/qwen3", true, listOf("high"), "high", true, false, false, false, false, true, wire = ReasoningWire.OLLAMA)
        assertEquals(listOf("off", "on"), ReasoningRules.choices(bool).map { it.id })
        assertEquals(JsonPrimitive(false), OllamaCloud.thinkValue(bool, off))
        assertEquals(JsonPrimitive(true), OllamaCloud.thinkValue(bool, on))
        val levels = bool.copy(supportedEfforts = listOf("high", "medium", "low"), mandatory = true, defaultEffort = "medium")
        assertEquals(listOf("high", "medium", "low"), ReasoningRules.choices(levels).map { it.id })
        assertEquals(JsonPrimitive("low"), OllamaCloud.thinkValue(levels, level("low")))
    }

    @Test fun localModelsAreAPlainSwitch() {
        val m = ModelReasoning.local("local/qwen3-4b", thinking = true)
        assertEquals(listOf("off", "on"), ReasoningRules.choices(m).map { it.id })
        assertEquals(false, ReasoningRules.resolve(m, auto).on)
        assertEquals(true, ReasoningRules.resolve(m, on).on)
        assertEquals("{}", fields(m, on))
        assertTrue(ReasoningRules.choices(ModelReasoning.local("local/x", thinking = false)).isEmpty())
    }

    @Test fun preferencesRoundTripThroughChoices() {
        assertEquals(auto, ReasoningRules.preferenceFor(ReasoningRules.AUTO))
        assertEquals(off, ReasoningRules.preferenceFor(ReasoningRules.OFF))
        assertEquals(on, ReasoningRules.preferenceFor(ReasoningRules.ON))
        assertEquals(level("low"), ReasoningRules.preferenceFor("low"))
    }

    @Test fun unsupportedAndUnknownSendNothing() {
        val unknown = ModelReasoning.unknown("custom/p/m", "no metadata")
        assertEquals(ReasoningSupport.UNKNOWN, unknown.support)
        assertEquals("{}", fields(unknown, on))
        assertTrue(ReasoningRules.choices(unknown).isEmpty())
        val none = ModelReasoning.unsupported("m")
        assertEquals(ReasoningSupport.UNSUPPORTED, none.support)
        assertNull(ReasoningRules.selected(none, on))
        assertEquals(JsonObject(emptyMap()), ReasoningRules.requestFields(none, on))
    }
}
