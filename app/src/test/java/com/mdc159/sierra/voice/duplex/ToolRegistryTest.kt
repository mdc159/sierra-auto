package com.mdc159.sierra.voice.duplex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private val bridge = RecordingBridge("Yes.")
    private val registry = ToolRegistry(listOf(AskAgentTool(bridge)))

    @Test
    fun askAgentForwardsTheQuestion() {
        val out = registry.run("ask_agent", """{"question":"Is the truck due for service?"}""")
        assertFalse(out.isError)
        assertEquals("Yes.", out.result)
        assertEquals("Is the truck due for service?", bridge.questions.take())
    }

    @Test
    fun askAgentWithoutAQuestionIsAnError() {
        val out = registry.run("ask_agent", "{}")
        assertTrue(out.isError)
        assertEquals("ask_agent needs a question.", out.result)
    }

    @Test
    fun unknownToolAndBadJsonAreErrors() {
        assertTrue(registry.run("nope", "{}").isError)
        assertTrue(registry.run("ask_agent", "not json").isError)
    }

    @Test
    fun bridgeFailureBecomesAnErrorResult() {
        val failing = ToolRegistry(listOf(AskAgentTool { throw IllegalStateException("Agent offline") }))
        val out = failing.run("ask_agent", """{"question":"hi"}""")
        assertTrue(out.isError)
        assertEquals("Agent offline", out.result)
    }

    @Test
    fun schemaIsAnObjectWithRequiredStringProperties() {
        val schema = AskAgentTool(bridge).parameters
        assertEquals("object", schema.getString("type"))
        assertEquals("string", schema.getJSONObject("properties").getJSONObject("question").getString("type"))
        assertEquals("question", schema.getJSONArray("required").getString(0))
        val two = ToolRegistry.stringSchema("a" to "A", "b" to "B", required = listOf("a"))
        assertEquals(1, two.getJSONArray("required").length())
        assertTrue(JSONObject(two.toString()).has("properties"))
    }
}
