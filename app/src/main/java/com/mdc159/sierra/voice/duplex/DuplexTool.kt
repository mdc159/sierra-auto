package com.mdc159.sierra.voice.duplex

import org.json.JSONObject

/**
 * A function the realtime voice may call. [parameters] is a JSON Schema object; [invoke]
 * is blocking and runs on a background thread. Return the text the model should read.
 */
interface DuplexTool {
    val name: String
    val description: String
    val parameters: JSONObject

    @Throws(Exception::class)
    fun invoke(arguments: JSONObject): String
}

/** The tools offered to a session, looked up by name when the service calls one. */
class ToolRegistry(tools: List<DuplexTool> = emptyList()) {
    private val byName = tools.associateBy { it.name }

    val all: List<DuplexTool> get() = byName.values.toList()

    operator fun get(name: String): DuplexTool? = byName[name]

    /**
     * Runs [name] with [argumentsJson]. Never throws: an unknown tool or a tool failure
     * becomes an error result so the model can tell the driver.
     */
    fun run(name: String, argumentsJson: String): ToolOutcome {
        val tool = byName[name] ?: return ToolOutcome("Unknown tool: $name", isError = true)
        val args = try {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        } catch (e: Exception) {
            return ToolOutcome("Arguments were not valid JSON.", isError = true)
        }
        return try {
            ToolOutcome(tool.invoke(args), isError = false)
        } catch (e: Exception) {
            ToolOutcome(e.message?.takeIf { it.isNotBlank() } ?: "Tool failed.", isError = true)
        }
    }

    companion object {
        /** Builds a JSON Schema object with string properties. */
        fun stringSchema(vararg props: Pair<String, String>, required: List<String> = props.map { it.first }): JSONObject {
            val properties = JSONObject()
            props.forEach { (name, description) ->
                properties.put(name, JSONObject().put("type", "string").put("description", description))
            }
            return JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", org.json.JSONArray(required))
        }
    }
}

data class ToolOutcome(val result: String, val isError: Boolean)

/** The owner's own agent, reachable from the realtime voice. */
fun interface AgentBridge {
    /** Blocking. Returns the agent's answer, or throws with a short message. */
    @Throws(Exception::class)
    fun ask(question: String): String
}

/** Built-in tool: hands a hard question to the owner's agent and returns its answer. */
class AskAgentTool(private val bridge: AgentBridge) : DuplexTool {
    override val name = NAME
    override val description =
        "Ask the owner's personal agent. Use it for anything that needs the owner's own data, " +
            "memory, tools or accounts, or for hard questions you cannot answer well yourself. " +
            "Pass the full question in plain English."
    override val parameters: JSONObject =
        ToolRegistry.stringSchema("question" to "The question for the owner's agent, in plain English.")

    override fun invoke(arguments: JSONObject): String {
        val question = arguments.optString("question").trim()
        if (question.isEmpty()) throw IllegalArgumentException("ask_agent needs a question.")
        return bridge.ask(question)
    }

    companion object {
        const val NAME = "ask_agent"
    }
}
