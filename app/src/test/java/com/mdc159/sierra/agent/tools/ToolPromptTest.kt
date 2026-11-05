package com.mdc159.sierra.agent.tools

import com.mdc159.sierra.agent.Conversation
import org.junit.Assert.assertTrue
import org.junit.Test

/** The system prompt has to tell the model about the tools and the spoken-reply rules. */
class ToolPromptTest {

    @Test
    fun systemPromptNamesEveryToolAndTheSpokenRules() {
        val prompt = Conversation.SYSTEM_PROMPT
        for (tool in listOf("find_places", "navigate_to", "file_idea")) {
            assertTrue("prompt mentions $tool", prompt.contains(tool))
        }
        assertTrue(prompt.contains("one to three short sentences"))
        assertTrue(prompt.contains("issue number"))
        assertTrue(prompt.lowercase().contains("never read a url"))
    }
}
