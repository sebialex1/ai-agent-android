package com.sebialex.agent

import com.sebialex.agent.data.backup.BackupBundle
import com.sebialex.agent.data.llm.MediaModelRef
import com.sebialex.agent.data.llm.MediaModels
import com.sebialex.agent.data.llm.ProviderMetadata
import com.sebialex.agent.data.llm.ProviderModelMeta
import com.sebialex.agent.data.llm.RoutedImageGenerator
import com.sebialex.agent.data.secure.SecureSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MediaModelsTest {
    private fun provider(id: String, url: String, models: List<String>, info: Map<String, ProviderModelMeta> = emptyMap()) =
        SecureSettings.CustomProvider(id = id, name = id.replaceFirstChar { it.uppercase() }, baseUrl = url, models = models, modelInfo = info)

    @Test fun openRouterImageCatalogKeepsImageOutputOnly() {
        val body = """{"data":[
            {"id":"google/gemini-2.5-flash-image","name":"Google: Nano Banana","architecture":{"output_modalities":["image","text"]}},
            {"id":"x-ai/grok-imagine-image-2.0","name":"xAI: Grok Imagine Image 2.0","architecture":{"output_modalities":["image"]}},
            {"id":"some/text-model","architecture":{"output_modalities":["text"]}},
            {"name":"no id"}
        ]}"""
        val list = MediaModels.parseOpenRouterImageModels(body)
        assertEquals(setOf("google/gemini-2.5-flash-image", "x-ai/grok-imagine-image-2.0"), list.map { it.ref.model }.toSet())
        assertTrue(list.all { it.ref.provider == MediaModels.PROVIDER_OPENROUTER })
        assertTrue(MediaModels.parseOpenRouterImageModels("not json").isEmpty())
    }

    @Test fun customProvidersListOnlyDocumentedOrAdvertisedImageModels() {
        val openai = provider("oa", "https://api.openai.com/v1", listOf("gpt-4o", "gpt-image-1", "dall-e-3", "o3"))
        assertEquals(listOf("gpt-image-1", "dall-e-3"), MediaModels.customImageModels(openai).map { it.ref.model })
        val gemini = provider("gm", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("gemini-2.5-flash", "gemini-2.5-flash-image", "gemini-3-pro-image-preview"))
        assertEquals(listOf("gemini-2.5-flash-image", "gemini-3-pro-image-preview"), MediaModels.customImageModels(gemini).map { it.ref.model })
        // Anthropic and unknown providers: no image models, whatever the names look like.
        val anthropic = provider("an", "https://api.anthropic.com/v1", listOf("claude-sonnet-4-5", "claude-image-thing"))
        assertTrue(MediaModels.customImageModels(anthropic).isEmpty())
        val groq = provider("gq", "https://api.groq.com/openai/v1", listOf("llama-3.3-70b", "gpt-image-1"))
        assertTrue(MediaModels.customImageModels(groq).isEmpty())
        // Metadata from the provider's own list wins: Together marks FLUX as type "image".
        val together = provider("tg", "https://api.together.xyz/v1", listOf("black-forest-labs/FLUX.1-schnell", "meta/llama"),
            mapOf("black-forest-labs/FLUX.1-schnell" to ProviderModelMeta(image = true), "meta/llama" to ProviderModelMeta(image = false)))
        assertEquals(listOf("black-forest-labs/FLUX.1-schnell"), MediaModels.customImageModels(together).map { it.ref.model })
        // ... and can also exclude a family match.
        val openaiMeta = provider("oa2", "https://api.openai.com/v1", listOf("gpt-image-1"), mapOf("gpt-image-1" to ProviderModelMeta(image = false)))
        assertTrue(MediaModels.customImageModels(openaiMeta).isEmpty())
    }

    @Test fun modelListTypeAndModalitiesAreParsed() {
        val meta = ProviderMetadata.fromModelList("""{"data":[
            {"id":"flux","type":"image"},{"id":"llama","type":"chat"},{"id":"plain"},
            {"id":"mm","architecture":{"output_modalities":["image","text"]}}
        ]}""")
        assertEquals(true, meta["flux"]?.image)
        assertEquals(false, meta["llama"]?.image)
        assertNull(meta["plain"])
        assertEquals(true, meta["mm"]?.image)
    }

    @Test fun sameModelIdOnTwoProvidersDoesNotCollide() {
        val a = MediaModels.customRef("a", "gpt-image-1")
        val b = MediaModels.customRef("b", "gpt-image-1")
        assertNotEquals(a, b)
        assertEquals("a", MediaModels.customProviderId(a))
        assertNull(MediaModels.customProviderId(MediaModelRef(MediaModels.PROVIDER_OPENROUTER, "gpt-image-1")))
    }

    @Test fun unavailableChoiceFailsLoudlyInsteadOfFallingBack() {
        val oa = provider("oa", "https://api.openai.com/v1", listOf("gpt-image-1"))
        val keys = mapOf("custom:oa" to 1, "openrouter" to 1)
        assertEquals("Oa" to oa, RoutedImageGenerator.resolve(MediaModels.customRef("oa", "gpt-image-1"), listOf(oa)) { keys[it] ?: 0 })
        fun fails(ref: MediaModelRef, providers: List<SecureSettings.CustomProvider>, count: (String) -> Int, text: String) {
            try {
                RoutedImageGenerator.resolve(ref, providers, count)
                fail("expected a failure for $ref")
            } catch (e: IOException) {
                assertTrue(e.message, e.message!!.contains(text))
                assertFalse(e.message, e.message!!.contains("using OpenRouter"))
            }
        }
        fails(MediaModels.customRef("gone", "gpt-image-1"), listOf(oa), { 1 }, "removed")
        fails(MediaModels.customRef("oa", "gpt-4o"), listOf(oa), { 1 }, "no longer lists")
        fails(MediaModels.customRef("oa", "gpt-image-1"), listOf(oa), { 0 }, "no Oa key")
        fails(MediaModels.DEFAULT, emptyList(), { 0 }, "no OpenRouter key")
        fails(MediaModelRef("weird", "x"), emptyList(), { 1 }, "not one this app knows")
        // A local server needs no key.
        val local = provider("lm", "http://192.168.1.5:1234/v1", listOf("flux"), mapOf("flux" to ProviderModelMeta(image = true)))
        assertEquals("Lm", RoutedImageGenerator.resolve(MediaModels.customRef("lm", "flux"), listOf(local)) { 0 }.first)
    }

    @Test fun imageRequestFieldsFollowEachProvidersDocs() {
        assertEquals(mapOf("size" to "1536x1024"), MediaModels.openAiImageFields("https://api.openai.com/v1", "gpt-image-1", "16:9"))
        assertEquals(mapOf("response_format" to "b64_json", "size" to "1024x1792"), MediaModels.openAiImageFields("https://api.openai.com/v1", "dall-e-3", "9:16"))
        assertEquals(mapOf("response_format" to "b64_json"), MediaModels.openAiImageFields("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash-image", "1:1"))
        assertTrue(MediaModels.openAiImageFields("https://api.x.ai/v1", "grok-imagine-image", "1:1").isEmpty())
    }

    @Test fun imageResponsesAreDecoded() {
        val png = byteArrayOf(1, 2, 3, 4)
        val b64 = java.util.Base64.getEncoder().encodeToString(png)
        val bytes = MediaModels.parseImageResponse("""{"data":[{"b64_json":"$b64","media_type":"image/webp"}]}""") as MediaModels.Payload.Bytes
        assertArrayEquals(png, bytes.bytes)
        assertEquals("image/webp", bytes.mime)
        val data = MediaModels.parseImageResponse("""{"data":[{"url":"data:image/jpeg;base64,$b64"}]}""") as MediaModels.Payload.Bytes
        assertEquals("image/jpeg", data.mime)
        val link = MediaModels.parseImageResponse("""{"data":[{"url":"https://x/y.png"}]}""") as MediaModels.Payload.Link
        assertEquals("https://x/y.png", link.url)
        try { MediaModels.parseImageResponse("""{"data":[]}"""); fail() } catch (_: IOException) {}
    }

    @Test fun mediaSelectionIsAPlainBackedUpSetting() {
        assertFalse(BackupBundle.isSecretPref(SecureSettings.KEY_MEDIA_IMAGE_PROVIDER))
        assertFalse(BackupBundle.isSecretPref(SecureSettings.KEY_MEDIA_IMAGE_MODEL))
        val text = BackupBundle.build(
            mapOf(SecureSettings.KEY_MEDIA_IMAGE_PROVIDER to "custom:oa", SecureSettings.KEY_MEDIA_IMAGE_MODEL to "gpt-image-1"),
            emptyList(), null, null,
        )
        val p = BackupBundle.parse(text)
        assertEquals("custom:oa", BackupBundle.primitiveValue(p.settings.getValue(SecureSettings.KEY_MEDIA_IMAGE_PROVIDER)))
        assertEquals("gpt-image-1", BackupBundle.primitiveValue(p.settings.getValue(SecureSettings.KEY_MEDIA_IMAGE_MODEL)))
    }
}
