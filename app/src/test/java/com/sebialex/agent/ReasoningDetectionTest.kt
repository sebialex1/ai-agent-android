package com.sebialex.agent

import com.sebialex.agent.data.backup.BackupBundle
import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.llm.CustomReasoning
import com.sebialex.agent.data.llm.ModelReasoning
import com.sebialex.agent.data.llm.ProviderMetadata
import com.sebialex.agent.data.llm.ReasoningPreference
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.llm.ReasoningSupport
import com.sebialex.agent.data.llm.ReasoningWire
import com.sebialex.agent.data.secure.SecureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningDetectionTest {
    private fun provider(id: String, url: String, models: List<String>, body: String? = null, gemini: String? = null) =
        SecureSettings.CustomProvider(
            id = id, name = id, baseUrl = url, models = models,
            modelInfo = (gemini?.let { ProviderMetadata.fromGeminiNative(it) }.orEmpty()) + (body?.let { ProviderMetadata.fromModelList(it) }.orEmpty()),
        )

    private fun detect(p: SecureSettings.CustomProvider, m: String, override: String? = null) =
        CustomReasoning.detect(CustomProviders.modelId(p.id, m), p, override)

    @Test fun anthropicCapabilitiesAreAuthoritative() {
        val body = """{"data":[
          {"id":"claude-opus-5","type":"model","capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true},"disabled":{"supported":true},"enabled":{"supported":false}}},
            "effort":{"supported":true,"low":{"supported":true},"medium":{"supported":true},"high":{"supported":true},"xhigh":{"supported":false},"max":{"supported":true}}}},
          {"id":"claude-3-haiku","type":"model","capabilities":{"thinking":{"supported":false,"types":{}}}}
        ]}"""
        val p = provider("an", "https://api.anthropic.com/v1", listOf("claude-opus-5", "claude-3-haiku", "claude-new"), body)
        val opus = detect(p, "claude-opus-5")
        assertEquals(ReasoningSupport.SUPPORTED, opus.support)
        assertEquals(ReasoningWire.ANTHROPIC, opus.wire)
        assertTrue(opus.adaptive)
        assertFalse(opus.surfacesText)
        assertEquals("Anthropic /v1/models capabilities", opus.source)
        assertEquals(listOf("max", "high", "medium", "low"), p.modelInfo["claude-opus-5"]!!.efforts)
        assertEquals("""{"thinking":{"type":"adaptive"}}""", ReasoningRules.requestFields(opus, ReasoningPreference(true, null)).toString())
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "claude-3-haiku").support)
        // Not in the saved list (no key at setup): unknown, not unsupported, and no guess from the name.
        assertEquals(ReasoningSupport.UNKNOWN, detect(p, "claude-new").support)
    }

    @Test fun geminiNativeThinkingFlagAndDocumentedOffSwitch() {
        val native = """{"models":[
          {"name":"models/gemini-2.5-flash","thinking":true},
          {"name":"models/gemini-2.5-pro","thinking":true},
          {"name":"models/gemini-2.0-flash","thinking":false},
          {"name":"models/gemma-3-27b-it"}
        ]}"""
        val p = provider("gm", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash", "gemma-3-27b-it"), gemini = native)
        val flash = detect(p, "gemini-2.5-flash")
        assertEquals(ReasoningWire.GEMINI, flash.wire)
        assertEquals(listOf("auto", "off", "high", "medium", "low"), ReasoningRules.choices(flash).map { it.id })
        assertEquals("""{"reasoning_effort":"none"}""", ReasoningRules.requestFields(flash, ReasoningPreference(false, null)).toString())
        val pro = detect(p, "gemini-2.5-pro")
        assertFalse(pro.canDisable)
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "gemini-2.0-flash").support)
        // Native list entry without the field: reported as not thinking by Gemini itself.
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "gemma-3-27b-it").support)
    }

    @Test fun geminiFamilyRuleOnlyWithoutMetadata() {
        val p = provider("gm", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-3-flash", "gemini-1.5-pro"))
        assertEquals(ReasoningSupport.SUPPORTED, detect(p, "gemini-3-flash").support)
        assertEquals(ReasoningSupport.UNKNOWN, detect(p, "gemini-1.5-pro").support)
    }

    @Test fun genericProvidersAreUnknownUntilMetadataOrOverride() {
        val p = provider("gq", "https://api.groq.com/openai/v1", listOf("qwen-qwq-32b", "llama-3.3-70b"))
        // No name-only guesses: "qwq" looks like a reasoning model but nothing says so.
        val qwq = detect(p, "qwen-qwq-32b")
        assertEquals(ReasoningSupport.UNKNOWN, qwq.support)
        assertTrue(ReasoningRules.choices(qwq).isEmpty())
        assertEquals("{}", ReasoningRules.requestFields(qwq, ReasoningPreference(true, "high")).toString())
        val manual = detect(p, "qwen-qwq-32b", CustomReasoning.OVERRIDE_SUPPORTED)
        assertEquals(CustomReasoning.MANUAL, manual.source)
        assertEquals(ReasoningWire.OPENAI_EFFORT, manual.wire)
        assertEquals("""{"reasoning_effort":"low"}""", ReasoningRules.requestFields(manual, ReasoningPreference(true, "low")).toString())
        val off = detect(p, "llama-3.3-70b", CustomReasoning.OVERRIDE_UNSUPPORTED)
        assertEquals(ReasoningSupport.UNSUPPORTED, off.support)
        assertEquals(CustomReasoning.MANUAL, off.source)
    }

    @Test fun supportedParametersInAModelListCount() {
        val body = """{"data":[{"id":"r1","supported_parameters":["tools","reasoning_effort"]},{"id":"plain","supported_parameters":["tools"]}]}"""
        val p = provider("lt", "http://192.168.1.2:4000/v1", listOf("r1", "plain"), body)
        assertEquals(ReasoningSupport.SUPPORTED, detect(p, "r1").support)
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "plain").support)
    }

    @Test fun openAiAndDeepSeekFamilies() {
        val oa = provider("oa", "https://api.openai.com/v1", listOf("o3", "gpt-5", "gpt-5-chat-latest", "gpt-4o"))
        assertEquals(ReasoningSupport.SUPPORTED, detect(oa, "o3").support)
        assertFalse(detect(oa, "o3").surfacesText)
        assertEquals(ReasoningSupport.SUPPORTED, detect(oa, "gpt-5").support)
        assertEquals(ReasoningSupport.UNKNOWN, detect(oa, "gpt-5-chat-latest").support)
        assertEquals(ReasoningSupport.UNKNOWN, detect(oa, "gpt-4o").support)
        val ds = provider("ds", "https://api.deepseek.com/v1", listOf("deepseek-reasoner"))
        val r = detect(ds, "deepseek-reasoner")
        assertEquals(listOf("on"), ReasoningRules.choices(r).map { it.id })
        assertTrue(r.surfacesText)
    }

    @Test fun removedProviderAndLocalUnknowns() {
        assertEquals(ReasoningSupport.UNKNOWN, CustomReasoning.detect("custom/x/y", null, null).support)
        assertEquals(ReasoningSupport.UNSUPPORTED, ModelReasoning.local("local/a", false).support)
        assertEquals(ReasoningSupport.SUPPORTED, ModelReasoning.local("local/a", true).support)
    }

    @Test fun overridesAndReasoningPrefsAreBackedUp() {
        val key = SecureSettings.KEY_REASONING_OVERRIDE_PREFIX + "custom/gq/qwen-qwq-32b"
        assertFalse(BackupBundle.isSecretPref(key))
        val p = BackupBundle.parse(BackupBundle.build(
            mapOf(key to CustomReasoning.OVERRIDE_SUPPORTED, "reasoning_effort::custom/gq/qwen-qwq-32b" to "low", "reasoning_enabled::custom/gq/qwen-qwq-32b" to true),
            emptyList(), null, null,
        ))
        assertEquals(CustomReasoning.OVERRIDE_SUPPORTED, BackupBundle.primitiveValue(p.settings.getValue(key)))
        assertEquals("low", BackupBundle.primitiveValue(p.settings.getValue("reasoning_effort::custom/gq/qwen-qwq-32b")))
        assertEquals(true, BackupBundle.primitiveValue(p.settings.getValue("reasoning_enabled::custom/gq/qwen-qwq-32b")))
    }

    @Test fun providerMetadataSurvivesTheProviderJson() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val p = provider("an", "https://api.anthropic.com/v1", listOf("c"), """{"data":[{"id":"c","capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true}}}}}]}""")
        val text = json.encodeToString(SecureSettings.CustomProvider.serializer(), p)
        val back = json.decodeFromString(SecureSettings.CustomProvider.serializer(), text)
        assertEquals(p, back)
        // Older saved providers (no modelInfo field) still load.
        val old = json.decodeFromString(SecureSettings.CustomProvider.serializer(), """{"id":"a","name":"A","baseUrl":"https://x/v1"}""")
        assertTrue(old.modelInfo.isEmpty())
    }
}
