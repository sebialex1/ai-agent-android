package com.sebialex.agent

import com.sebialex.agent.data.llm.GeminiVideoProtocol
import com.sebialex.agent.data.llm.OpenRouterBrain
import com.sebialex.agent.domain.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Gemini Interactions video protocol and the generate_video tool call, without a network. */
class GeminiVideoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun response(videoPart: String) = """
        {"steps":[
          {"type":"user_input","content":[{"type":"text","text":"a sunset"}]},
          {"type":"thought","content":[{"type":"thought","text":"thinking"}]},
          {"type":"model_output","content":[$videoPart]}
        ],"id":"v1_abc","status":"completed","model":"gemini-omni-1.2-flash","object":"interaction"}
    """.trimIndent()

    @Test fun findsTheVideoUriInsideSteps() {
        val part = GeminiVideoProtocol.videoPart(
            response("""{"type":"video","mime_type":"video/mp4","uri":"https://generativelanguage.googleapis.com/v1beta/files/abc-123:download?alt=media"}"""),
        )!!
        assertEquals("video/mp4", part.mime)
        assertNull(part.data)
        assertEquals("abc-123", GeminiVideoProtocol.fileId(part.uri!!))
    }

    @Test fun findsInlineBase64Video() {
        val part = GeminiVideoProtocol.videoPart(response("""{"type":"video","mime_type":"video/mp4","data":"AAAAIGZ0eXA"}"""))!!
        assertEquals("AAAAIGZ0eXA", part.data)
        assertNull(part.uri)
    }

    @Test fun noVideoPartIsNotFoundRatherThanGuessed() {
        assertNull(GeminiVideoProtocol.videoPart(response("""{"type":"text","text":"sorry, blocked"}""")))
        assertNull(GeminiVideoProtocol.videoPart("""{"steps":[]}"""))
        assertNull(GeminiVideoProtocol.videoPart("not json"))
        // A video part with neither data nor uri proves nothing.
        assertNull(GeminiVideoProtocol.videoPart(response("""{"type":"video","mime_type":"video/mp4"}""")))
    }

    @Test fun fileIdReadsBothUriShapes() {
        assertEquals("abc-123", GeminiVideoProtocol.fileId("files/abc-123"))
        assertEquals("abc-123", GeminiVideoProtocol.fileId("https://x.googleapis.com/v1beta/files/abc-123:download?alt=media"))
        assertNull(GeminiVideoProtocol.fileId("https://example.com/other/abc"))
    }

    @Test fun fileStateIsReadAndMissingIsNull() {
        assertEquals("ACTIVE", GeminiVideoProtocol.fileState("""{"name":"files/abc","state":"ACTIVE"}"""))
        assertEquals("FAILED", GeminiVideoProtocol.fileState("""{"state":"FAILED"}"""))
        assertNull(GeminiVideoProtocol.fileState("""{"name":"files/abc"}"""))
        assertNull(GeminiVideoProtocol.fileState("not json"))
    }

    @Test fun providerErrorIsSurfaced() {
        assertEquals("bad key", GeminiVideoProtocol.error("""{"error":{"message":"bad key","code":401}}"""))
        assertNull(GeminiVideoProtocol.error("""{"steps":[]}"""))
    }

    @Test fun requestBodySendsOnlySupportedValues() {
        val body = json.parseToJsonElement(
            GeminiVideoProtocol.requestBody("a sunset", "9:16", 12, "1080p"),
        ).jsonObject
        assertEquals(GeminiVideoProtocol.MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals("a sunset", body["input"]!!.jsonPrimitive.content)
        val format = body["response_format"]!!.jsonObject
        assertEquals("video", format["type"]!!.jsonPrimitive.content)
        // URI delivery: a video with audio is usually over the inline size limit.
        assertEquals("uri", format["delivery"]!!.jsonPrimitive.content)
        assertEquals("9:16", format["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("12s", format["duration"]!!.jsonPrimitive.content)
        assertEquals("1080p", format["resolution"]!!.jsonPrimitive.content)
    }

    @Test fun unsupportedValuesAreOmittedNotSent() {
        val format = json.parseToJsonElement(
            GeminiVideoProtocol.requestBody("x", "1:1", 99, "8k"),
        ).jsonObject["response_format"]!!.jsonObject
        assertFalse("aspect_ratio" in format)
        assertFalse("duration" in format)
        assertFalse("resolution" in format)
        // Nulls keep the provider's own defaults.
        val bare = json.parseToJsonElement(GeminiVideoProtocol.requestBody("x", null, null, null))
            .jsonObject["response_format"]!!.jsonObject
        assertEquals(setOf("type", "delivery"), bare.keys)
    }

    @Test fun generateVideoParsesAndClamps() {
        val c = OpenRouterBrain.parseToolCall(
            "1", "generate_video",
            """{"prompt":"a sunset","aspect_ratio":"9:16","seconds":"12","resolution":"720p","filename":"clips/sunset"}""",
        ) as ToolCall.GenerateVideo
        assertEquals("a sunset", c.prompt)
        assertEquals("9:16", c.aspectRatio)
        assertEquals(12, c.seconds)
        assertEquals("720p", c.resolution)
        assertEquals("clips/sunset", c.filename)
        // Out of range falls back to the provider default rather than sending an invalid value.
        val out = OpenRouterBrain.parseToolCall("2", "generate_video", """{"prompt":"x","seconds":99}""") as ToolCall.GenerateVideo
        assertNull(out.seconds)
        assertTrue(OpenRouterBrain.parseToolCall("3", "generate_video", "{}") is ToolCall.Invalid)
    }

    @Test fun generateVideoIsOfferedToCloudModelsAndNamedInTheLists() {
        assertTrue("\"generate_video\"" in OpenRouterBrain.toolsFor(memory = false).toString())
        assertTrue("generate_video" in OpenRouterBrain.TOOL_NAMES)
        assertTrue("generate_video" in OpenRouterBrain.TOOL_SUMMARY)
        assertTrue("generate_video" in OpenRouterBrain.SYSTEM_PROMPT)
        // Round trip: the wire arguments parse back to the same call.
        val call = ToolCall.GenerateVideo("id", "a sunset", "clips/s.mp4", "9:16", 8, "720p")
        val back = OpenRouterBrain.parseToolCall("id", OpenRouterBrain.toolName(call), OpenRouterBrain.arguments(call))
        assertEquals(call, back)
    }
}