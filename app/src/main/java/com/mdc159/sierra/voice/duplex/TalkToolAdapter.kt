package com.mdc159.sierra.voice.duplex

import com.mdc159.sierra.agent.tools.Tool
import com.mdc159.sierra.agent.tools.ToolException
import org.json.JSONObject

/**
 * Lets the Talk screen's tools (find_places, navigate_to, file_idea, later read_vehicle)
 * be called from a realtime voice session. Both interfaces are (name, schema, invoke);
 * a [ToolException] becomes the error text the model relays to the driver.
 */
class TalkToolAdapter(private val tool: Tool) : DuplexTool {
    override val name: String get() = tool.name
    override val description: String get() = tool.description
    override val parameters: JSONObject get() = tool.parameters

    override fun invoke(arguments: JSONObject): String = try {
        tool.run(arguments)
    } catch (e: ToolException) {
        throw IllegalStateException(e.message ?: "Tool failed.", e)
    }

    companion object {
        fun adapt(tools: List<Tool>): List<DuplexTool> = tools.map { TalkToolAdapter(it) }
    }
}
