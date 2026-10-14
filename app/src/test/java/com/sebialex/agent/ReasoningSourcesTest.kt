package com.sebialex.agent

import com.sebialex.agent.data.llm.CustomProviders
import com.sebialex.agent.data.llm.CustomReasoning
import com.sebialex.agent.data.llm.OllamaCloud
import com.sebialex.agent.data.llm.ProviderMetadata
import com.sebialex.agent.data.llm.ProviderModelMeta
import com.sebialex.agent.data.llm.ReasoningPreference
import com.sebialex.agent.data.llm.ReasoningRules
import com.sebialex.agent.data.llm.ReasoningSupport
import com.sebialex.agent.data.llm.ReasoningText
import com.sebialex.agent.data.llm.ReasoningWire
import com.sebialex.agent.data.secure.SecureSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reasoning metadata and reasoning text from the less common provider shapes. */
class ReasoningSourcesTest {
    private fun provider(url: String, models: List<String>, info: Map<String, ProviderModelMeta>) =
        SecureSettings.CustomProvider(id = "p", name = "p", baseUrl = url, models = models, modelInfo = info)

    private fun detect(p: SecureSettings.CustomProvider, m: String, override: String? = null) =
        CustomReasoning.detect(CustomProviders.modelId(p.id, m), p, override)

    private fun fields(p: SecureSettings.CustomProvider, m: String, pref: ReasoningPreference) =
        ReasoningRules.requestFields(detect(p, m), pref).toString()

    @Test fun vercelGatewayReasoningOptionsAndTags() {
        // Shapes as served by https://ai-gateway.vercel.sh/v1/models.
        val body = """{"object":"list","data":[
          {"id":"alibaba/qwen-3-14b","type":"language","tags":["reasoning","tool-use"],"modalities":{"input":["text"],"output":["text"]},
           "supported_parameters":["max_tokens","tools","reasoning","include_reasoning"],"reasoning_options":[{"type":"toggle"}]},
          {"id":"openai/gpt-5","type":"language","tags":["reasoning"],"supported_parameters":["tools","reasoning"],
           "reasoning_options":[{"type":"effort","values":["none","low","medium","high","xhigh"]}]},
          {"id":"google/imagen","type":"image","tags":["image-generation"],"modalities":{"input":["text"],"output":["image"]}},
          {"id":"tagged/only","tags":["reasoning"]},
          {"id":"plain/chat","type":"language","tags":["tool-use"],"supported_parameters":["tools"]}
        ]}"""
        val info = ProviderMetadata.fromModelList(body)
        val p = provider("https://ai-gateway.vercel.sh/v1", info.keys.toList(), info)
        val qwen = detect(p, "alibaba/qwen-3-14b")
        assertEquals(ReasoningWire.OPENROUTER, qwen.wire)
        assertEquals(listOf("auto", "off", "on"), ReasoningRules.choices(qwen).map { it.id })
        assertEquals("""{"reasoning":{"enabled":false}}""", fields(p, "alibaba/qwen-3-14b", ReasoningPreference(false, null)))
        assertEquals("""{"reasoning":{"enabled":true}}""", fields(p, "alibaba/qwen-3-14b", ReasoningPreference(true, null)))
        val gpt = detect(p, "openai/gpt-5")
        assertEquals(listOf("auto", "off", "xhigh", "high", "medium", "low"), ReasoningRules.choices(gpt).map { it.id })
        assertEquals("""{"reasoning":{"effort":"none"}}""", fields(p, "openai/gpt-5", ReasoningPreference(false, null)))
        assertEquals("""{"reasoning":{"effort":"low"}}""", fields(p, "openai/gpt-5", ReasoningPreference(true, "low")))
        assertEquals(true, info["google/imagen"]!!.image)
        assertEquals("model list tags", info["tagged/only"]!!.source)
        assertEquals(ReasoningSupport.SUPPORTED, detect(p, "tagged/only").support)
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "plain/chat").support)
    }

    @Test fun lmStudioNativeCapabilities() {
        // https://lmstudio.ai/docs/developer/rest/list
        val body = """{"models":[
          {"type":"llm","key":"google/gemma-4-26b-a4b","capabilities":{"vision":true,"trained_for_tool_use":true,"reasoning":{"allowed_options":["off","on"],"default":"on"}}},
          {"type":"llm","key":"deepseek-r1","capabilities":{"vision":false,"trained_for_tool_use":true,"reasoning":{"allowed_options":["on"],"default":"on"}}},
          {"type":"llm","key":"qwen2.5-7b","capabilities":{"vision":false,"trained_for_tool_use":true}},
          {"type":"embedding","key":"nomic-embed"}
        ]}"""
        val info = ProviderMetadata.fromModelList(body)
        assertTrue(listOf("google/gemma-4-26b-a4b", "deepseek-r1", "qwen2.5-7b").all { it in info })
        val p = provider("http://192.168.1.5:1234/v1", info.keys.toList(), info)
        val gemma = detect(p, "google/gemma-4-26b-a4b")
        assertEquals(ReasoningSupport.SUPPORTED, gemma.support)
        // No documented switch on LM Studio's chat endpoint: shown as thinking, nothing sent.
        assertEquals(listOf("on"), ReasoningRules.choices(gemma).map { it.id })
        assertEquals("{}", fields(p, "google/gemma-4-26b-a4b", ReasoningPreference(false, null)))
        assertEquals(ReasoningSupport.SUPPORTED, detect(p, "deepseek-r1").support)
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "qwen2.5-7b").support)
    }

    @Test fun localOllamaShowCapabilities() {
        val levels = ProviderMetadata.fromOllamaShow(
            """{"capabilities":["completion","tools","thinking"],"thinking":{"values":["low","medium","high"],"default":"medium"},"details":{"family":"gptoss"}}""",
        )!!
        val bool = ProviderMetadata.fromOllamaShow("""{"capabilities":["completion","thinking"],"thinking":{"values":[false,true],"default":true}}""")!!
        val none = ProviderMetadata.fromOllamaShow("""{"capabilities":["completion","tools"]}""")!!
        assertNull(ProviderMetadata.fromOllamaShow("""{"license":"x"}"""))
        val p = provider("http://localhost:11434/v1", listOf("gpt-oss:20b", "qwen3:8b", "llama3.2"),
            mapOf("gpt-oss:20b" to levels, "qwen3:8b" to bool, "llama3.2" to none))
        assertEquals(listOf("auto", "high", "medium", "low"), ReasoningRules.choices(detect(p, "gpt-oss:20b")).map { it.id })
        assertEquals("""{"reasoning_effort":"low"}""", fields(p, "gpt-oss:20b", ReasoningPreference(true, "low")))
        val qwen = detect(p, "qwen3:8b")
        assertEquals(listOf("auto", "off", "on"), ReasoningRules.choices(qwen).map { it.id })
        // Ollama's OpenAI compatibility: "none" requests false, a recognised effort true.
        assertEquals("""{"reasoning_effort":"none"}""", fields(p, "qwen3:8b", ReasoningPreference(false, null)))
        assertEquals("""{"reasoning_effort":"high"}""", fields(p, "qwen3:8b", ReasoningPreference(true, null)))
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "llama3.2").support)
        // The manual override still wins over metadata.
        assertEquals(ReasoningSupport.UNSUPPORTED, detect(p, "qwen3:8b", CustomReasoning.OVERRIDE_UNSUPPORTED).support)
    }

    @Test fun ollamaDetailsFamilyFallback() {
        val details = { s: String -> Json.parseToJsonElement(s) as JsonObject }
        assertTrue(OllamaCloud.knownThinkingFamily(details("""{"family":"gptoss","families":["gptoss"]}""")))
        assertTrue(OllamaCloud.knownThinkingFamily(details("""{"family":"qwen3"}""")))
        assertFalse(OllamaCloud.knownThinkingFamily(details("""{"family":"llama"}""")))
        assertFalse(OllamaCloud.knownThinkingFamily(null))
    }

    @Test fun openRouterStyleBlockInACustomList() {
        val body = """{"data":[{"id":"x/r","reasoning":{"supported_efforts":["high","low"],"mandatory":true}}]}"""
        val info = ProviderMetadata.fromModelList(body)
        assertEquals(listOf("high", "low"), info["x/r"]!!.efforts)
        assertEquals(false, info["x/r"]!!.canDisable)
        val p = provider("https://gateway.example.com/v1", listOf("x/r"), info)
        assertFalse(detect(p, "x/r").canDisable)
    }

    @Test fun inlineThinkBlocksAreSeparatedWhileStreaming() {
        val s = ReasoningText.InlineSplitter()
        var content = ""
        var reasoning = ""
        listOf("  <thi", "nk>Let me ", "check.</th", "ink>\n\nThe answer", " is 4.").forEach { c ->
            val p = s.feed(c); content += p.content; reasoning += p.reasoning
        }
        s.finish().let { content += it.content; reasoning += it.reasoning }
        assertEquals("The answer is 4.", content)
        assertEquals("Let me check.", reasoning)
        // A tag that does not open the answer is the answer.
        assertEquals("Use <think> tags like this", ReasoningText.splitInline("Use <think> tags like this").content)
        // JSON answers stream through untouched.
        assertEquals("{\"a\": 1}", ReasoningText.splitInline("{\"a\": 1}").content)
        assertEquals("Summary" to "plan", ReasoningText.splitInline("<thought>plan</thought>Summary").let { it.content to it.reasoning })
        // Unclosed block: the text is given back as the answer, never lost.
        val u = ReasoningText.InlineSplitter()
        val r = u.feed("<think>only this") + u.finish()
        assertEquals("only this", r.content)
        assertTrue(u.reclaimed)
    }

    @Test fun mistralThinkingChunksAndThinkingField() {
        val parts = Json.parseToJsonElement(
            """[{"type":"thinking","thinking":[{"type":"text","text":"Step 1."}]},{"type":"text","text":"Done."}]""",
        )
        assertEquals("Done." to "Step 1.", ReasoningText.split(parts))
        val delta = Json.parseToJsonElement("""{"content":"","thinking":"hmm"}""") as JsonObject
        assertEquals("hmm", ReasoningText.plain(delta))
        val both = Json.parseToJsonElement("""{"reasoning":"a","reasoning_content":"b"}""") as JsonObject
        assertEquals("a", ReasoningText.plain(both))
    }
}
