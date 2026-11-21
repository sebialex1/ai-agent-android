package com.sebialex.agent

import com.sebialex.agent.data.llm.CustomProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure URL handling behind custom/local providers: whatever is typed (an Ollama instance on
 * another LAN box, a cloud host, a full endpoint pasted in) must normalize to a callable API
 * root, and local addresses must be recognized as cleartext-HTTP candidates everywhere.
 */
class CustomProvidersTest {

    @Test
    fun aLanIpWithoutASchemeGetsHttp() {
        assertEquals("http://192.168.1.130:11434", CustomProviders.normalize("192.168.1.130:11434"))
        assertEquals("http://my-box.local:8080", CustomProviders.normalize("my-box.local:8080"))
    }

    @Test
    fun aLanIpKeepsHttpAndCloudStaysHttps() {
        assertEquals("http://192.168.1.130:11434", CustomProviders.normalize("http://192.168.1.130:11434"))
        assertEquals("http://10.0.0.5:1234/v1", CustomProviders.normalize("10.0.0.5:1234/v1"))
        assertEquals("https://api.groq.com/openai/v1", CustomProviders.normalize("https://api.groq.com/openai/v1"))
    }

    @Test
    fun pastedEndpointSuffixesAreStripped() {
        assertEquals(
            "http://192.168.1.130:11434/v1",
            CustomProviders.normalize("http://192.168.1.130:11434/v1/models"),
        )
        assertEquals(
            "http://192.168.1.130:11434/v1",
            CustomProviders.normalize("http://192.168.1.130:11434/v1/chat/completions"),
        )
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", CustomProviders.normalize("   "))
        assertNull(CustomProviders.parse("not-a-custom-model"))
    }

    @Test
    fun localAddressesCoverThePrivateRanges() {
        assertTrue(CustomProviders.isLocalAddress("http://192.168.1.130:11434/v1"))
        assertTrue(CustomProviders.isLocalAddress("http://localhost:11434"))
        assertTrue(CustomProviders.isLocalAddress("http://127.0.0.1:8080"))
        assertTrue(CustomProviders.isLocalAddress("http://10.1.2.3:8000"))
        assertTrue(CustomProviders.isLocalAddress("http://172.20.0.5:8000"))
        assertTrue(CustomProviders.isLocalAddress("http://nas.local:1234"))
        assertFalse(CustomProviders.isLocalAddress("https://api.openai.com/v1"))
        assertFalse(CustomProviders.isLocalAddress("https://openrouter.ai/api/v1"))
    }

    @Test
    fun inferenceAndDiscoveryUseTheSameRoot() {
        assertEquals(
            "http://192.168.1.130:11434/v1/chat/completions",
            CustomProviders.chatEndpoint("http://192.168.1.130:11434/v1"),
        )
        assertEquals(
            "http://192.168.1.130:11434/v1/responses",
            CustomProviders.responsesEndpoint("http://192.168.1.130:11434/v1"),
        )
    }

    @Test
    fun localPresetsSpeakClearhttpAndTheChatApi() {
        val local = CustomProviders.PRESETS.filter { it.local }
        assertTrue("there are local presets", local.isNotEmpty())
        local.forEach {
            assertTrue("${it.id} must start with http:// for cleartext LAN use", it.baseUrl.startsWith("http://"))
        }
        assertTrue(CustomProviders.preset("ollama-local")!!.local)
    }
}
