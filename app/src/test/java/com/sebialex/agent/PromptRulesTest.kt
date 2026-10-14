package com.sebialex.agent

import com.sebialex.agent.data.llm.OpenRouterBrain
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
}
