package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRulesTest {
    @Test
    fun bothPromptsForbidToolCallJsonInTheAnswer() {
        for (prompt in listOf(OpenRouterBrain.SYSTEM_PROMPT, OpenRouterBrain.COMPACT_SYSTEM_PROMPT)) {
            assertTrue("{\"action\": ..." in prompt)
            assertTrue("not executed" in prompt.lowercase())
            assertTrue("attached" in prompt)
        }
    }

    @Test
    fun generateImageNoLongerPromisesAnAutomaticAttachment() {
        assertFalse("attached automatically" in OpenRouterBrain.SYSTEM_PROMPT)
        val tools = OpenRouterBrain.toolsFor(memory = false).toString()
        assertFalse("attached to your answer automatically" in tools)
    }

    @Test
    fun blankCustomInstructionsAddNothingToThePrompt() {
        val base = com.sebialex.agent.domain.agent.BrainContext("task", emptyList(), emptyList(), emptyList())
        assertEquals("", base.customSection())
        assertEquals("", base.copy(customPrompt = "   \n ").customSection())
        val system = systemPromptOf(base)
        assertFalse("The user's own instructions" in system)
    }

    @Test
    fun theSystemPromptCarriesTheUsersOwnInstructions() {
        val ctx = com.sebialex.agent.domain.agent.BrainContext(
            "task", emptyList(), emptyList(), emptyList(), customPrompt = "  Always answer in Romanian.  ",
        )
        val section = ctx.customSection()
        assertTrue(section.contains("Always answer in Romanian."))
        assertFalse(section.contains("  Always"))
        val system = systemPromptOf(ctx)
        assertTrue("The user's own instructions for this conversation" in system)
        assertTrue("Always answer in Romanian." in system)
        // The built-in rules are still there: the addition is on top, not a replacement.
        assertTrue(OpenRouterBrain.SYSTEM_PROMPT.trim() in system)
    }

    @Test
    fun promptsOwnCreativeAsksInsteadOfAskingWhatTheUserWants() {
        assertTrue("the forbidden helpdesk opener is named", "How can I assist you today?" in OpenRouterBrain.SYSTEM_PROMPT)
        for (prompt in listOf(OpenRouterBrain.SYSTEM_PROMPT, OpenRouterBrain.COMPACT_SYSTEM_PROMPT)) {
            assertTrue("invent the subject" in prompt.lowercase())
        }
    }

    @Test
    fun thePromptMakesBatchedToolCallsTheDefault() {
        assertTrue("Batch your work" in OpenRouterBrain.SYSTEM_PROMPT)
        assertTrue("SAME step" in OpenRouterBrain.SYSTEM_PROMPT)
    }

    @Test
    fun volatilePromptPartsSitAfterTheStablePrefix() {
        val ctx = com.sebialex.agent.domain.agent.BrainContext(
            "task", emptyList(), emptyList(), listOf("notes.txt"),
            todos = listOf(com.sebialex.agent.domain.model.TodoItem(1, "do it", false)),
        )
        val system = systemPromptOf(ctx)
        // The instruction block leads byte for byte; per-turn facts trail it so a provider
        // caches the stable prefix across turns.
        assertTrue(system.startsWith(OpenRouterBrain.SYSTEM_PROMPT))
        val date = system.indexOf("Today is ")
        val files = system.indexOf("Workspace files:")
        val todos = system.indexOf("This conversation's todo list")
        assertTrue(date in 0..files && files in 0..todos)
    }

    /** The system message actually sent for [ctx], as the brain builds it. */
    private fun systemPromptOf(ctx: com.sebialex.agent.domain.agent.BrainContext): String {
        val messages = OpenRouterBrain(apiKey = "k", model = "m").messages(ctx)
        return messages[0].jsonObject["content"]!!.jsonPrimitive.content
    }
}
