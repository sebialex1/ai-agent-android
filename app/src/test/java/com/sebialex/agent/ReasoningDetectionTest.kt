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
import org.junit.Assert.assertNull
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

    /**
     * The request shapes the compatibility endpoints document (checked against the docs on
     * 2026-10-14): Anthropic's OpenAI SDK page lists `reasoning_effort` as "Ignored" and takes
     * `thinking`; its per-model table rejects `enabled` on 4.7+ and `adaptive` on 4.5 and earlier,
     * and `disabled` on always-on models. Gemini's OpenAI page maps `reasoning_effort`
     * low/medium/high, `none` only for 2.5 models other than Pro; 3 Pro has low/high levels only.
     */
    @Test fun compatibilityEndpointContractsFromTheDocs() {
        val fields = { r: com.sebialex.agent.data.llm.ModelReasoning, p: ReasoningPreference -> ReasoningRules.requestFields(r, p).toString() }
        val on = ReasoningPreference(true, null)
        val off = ReasoningPreference(false, null)
        // Anthropic, capabilities saved: Off only where `disabled` is supported; never reasoning_effort.
        val body = """{"data":[
          {"id":"claude-opus-5-5","capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true},"disabled":{"supported":false},"enabled":{"supported":false}}}}},
          {"id":"claude-sonnet-5","capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":true},"disabled":{"supported":true},"enabled":{"supported":false}}}}},
          {"id":"claude-haiku-4-5","capabilities":{"thinking":{"supported":true,"types":{"adaptive":{"supported":false},"disabled":{"supported":true},"enabled":{"supported":true}}}}}
        ]}"""
        val an = provider("an", "https://api.anthropic.com/v1", listOf("claude-opus-5-5", "claude-sonnet-5", "claude-haiku-4-5", "claude-opus-4-7", "claude-sonnet-4-5-20250929", "claude-3-7-sonnet-20250219"), body)
        val opus55 = detect(an, "claude-opus-5-5")
        assertEquals(listOf("auto", "on"), ReasoningRules.choices(opus55).map { it.id })
        assertEquals("""{"thinking":{"type":"adaptive"}}""", fields(opus55, on))
        assertEquals("""{"thinking":{"type":"adaptive"}}""", fields(opus55, off))
        val sonnet5 = detect(an, "claude-sonnet-5")
        assertEquals(listOf("auto", "off", "on"), ReasoningRules.choices(sonnet5).map { it.id })
        assertEquals("""{"thinking":{"type":"disabled"}}""", fields(sonnet5, off))
        val haiku45 = detect(an, "claude-haiku-4-5")
        assertTrue(fields(haiku45, on).startsWith("""{"thinking":{"type":"enabled","budget_tokens":"""))
        listOf(opus55, sonnet5, haiku45).forEach { m ->
            ReasoningRules.choices(m).forEach { c -> assertFalse(fields(m, ReasoningRules.preferenceFor(c.id)).contains("reasoning_effort")) }
        }
        // Anthropic, manual override without capabilities: no Off (it can be a 400), and the
        // thinking type follows Anthropic's per-model table.
        val opus47 = detect(an, "claude-opus-4-7", CustomReasoning.OVERRIDE_SUPPORTED)
        assertFalse(opus47.canDisable)
        assertEquals(listOf("auto", "on"), ReasoningRules.choices(opus47).map { it.id })
        assertEquals("""{"thinking":{"type":"adaptive"}}""", fields(opus47, on))
        assertFalse(detect(an, "claude-sonnet-4-5-20250929", CustomReasoning.OVERRIDE_SUPPORTED).adaptive)
        assertFalse(detect(an, "claude-3-7-sonnet-20250219", CustomReasoning.OVERRIDE_SUPPORTED).adaptive)
        assertTrue(CustomReasoning.claudeUsesAdaptive("claude-opus-4-6"))
        assertTrue(CustomReasoning.claudeUsesAdaptive("claude-haiku-5-5"))
        assertFalse(CustomReasoning.claudeUsesAdaptive("claude-opus-4-20250514"))
        assertFalse(CustomReasoning.claudeUsesAdaptive("claude-opus-4-1-20250805"))

        // Gemini: reasoning_effort low/medium/high; 3 Pro low/high; "none" only on 2.5 Flash / Flash-Lite.
        val gm = provider("gm", "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("gemini-3-pro-preview", "gemini-3.1-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-3.8-flash"))
        assertEquals(listOf("auto", "high", "low"), ReasoningRules.choices(detect(gm, "gemini-3-pro-preview")).map { it.id })
        assertEquals(listOf("auto", "high", "medium", "low"), ReasoningRules.choices(detect(gm, "gemini-3.1-pro-preview")).map { it.id })
        assertFalse(fields(detect(gm, "gemini-3-pro-preview"), ReasoningPreference(true, "medium")).contains("medium"))
        assertEquals("""{"reasoning_effort":"medium"}""", fields(detect(gm, "gemini-3.8-flash"), ReasoningPreference(true, "medium")))
        assertEquals("{}", fields(detect(gm, "gemini-2.5-pro"), off))
        assertEquals("{}", fields(detect(gm, "gemini-3.8-flash"), off))
        val lite = detect(gm, "gemini-2.5-flash-lite")
        assertEquals("""{"reasoning_effort":"none"}""", fields(lite, off))
        assertFalse(lite.defaultOn)
        // The Settings copy names exactly these limits.
        assertTrue(CustomReasoning.compatibilityNote("https://api.anthropic.com/v1")!!.contains("ignores reasoning_effort"))
        assertTrue(CustomReasoning.compatibilityNote("https://generativelanguage.googleapis.com/v1beta/openai")!!.contains("Low/High on Gemini 3 Pro"))
        assertNull(CustomReasoning.compatibilityNote("https://api.groq.com/openai/v1"))
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
