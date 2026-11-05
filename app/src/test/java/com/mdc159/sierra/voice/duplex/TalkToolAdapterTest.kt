package com.mdc159.sierra.voice.duplex

import com.mdc159.sierra.agent.tools.Tool
import com.mdc159.sierra.agent.tools.ToolException
import com.mdc159.sierra.agent.tools.ToolSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkToolAdapterTest {

    private val echo = object : Tool {
        override val name = "echo"
        override val description = "Repeats the text."
        override val parameters: JSONObject = ToolSchema.obj("text" to ToolSchema.string("What to repeat"), required = listOf("text"))
        override fun status(args: JSONObject) = "Repeating…"
        override fun run(args: JSONObject): String {
            val text = args.optString("text")
            if (text == "boom") throw ToolException("No network for that.")
            return JSONObject().put("echo", text).toString()
        }
    }

    @Test
    fun adaptedToolKeepsNameSchemaAndResult() {
        val registry = ToolRegistry(TalkToolAdapter.adapt(listOf(echo)))
        val tool = registry["echo"]!!
        assertEquals("Repeats the text.", tool.description)
        assertEquals("string", tool.parameters.getJSONObject("properties").getJSONObject("text").getString("type"))
        val out = registry.run("echo", """{"text":"hi"}""")
        assertFalse(out.isError)
        assertEquals("hi", JSONObject(out.result).getString("echo"))
    }

    @Test
    fun toolExceptionBecomesAnErrorOutcomeWithItsMessage() {
        val out = ToolRegistry(TalkToolAdapter.adapt(listOf(echo))).run("echo", """{"text":"boom"}""")
        assertTrue(out.isError)
        assertEquals("No network for that.", out.result)
    }
}
