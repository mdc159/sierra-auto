package com.mdc159.sierra.agent.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Something the agent can do by name: look up places, start navigation, file an idea.
 * A later lane adds `read_vehicle` (OBD) by implementing this interface and adding the
 * instance to the list TalkScreen hands to [com.mdc159.sierra.agent.AgentClient.chat].
 *
 * Implementations must be safe to call from a background thread and must not touch the
 * car screen directly; anything that has to run on the main thread goes through a
 * callback the caller provides.
 */
interface Tool {
    /** The function name the model calls, `[a-z_]+`. */
    val name: String

    /** Tells the model when the tool fits. One or two sentences. */
    val description: String

    /** JSON Schema for the arguments object (`{"type":"object","properties":{...}}`). */
    val parameters: JSONObject

    /** Short line for the car screen while the tool runs, for example "Finding fuel nearby…". */
    fun status(args: JSONObject): String

    /**
     * Does the work and returns the result the model reads, as a short JSON string.
     * Throw [ToolException] with a plain-English message for anything the model should
     * relay to the driver.
     */
    @Throws(ToolException::class)
    fun run(args: JSONObject): String

    /** The OpenAI-compatible `tools` entry for this tool. */
    fun spec(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters),
        )
}

/** A tool failure the model should explain to the driver. */
class ToolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Small helpers for building parameter schemas and results without string interpolation. */
object ToolSchema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        props.forEach { (k, v) -> properties.put(k, v) }
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
        return schema
    }

    fun string(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)

    fun number(description: String): JSONObject = JSONObject().put("type", "number").put("description", description)

    /** `{"error": message}`: what the model sees when a tool cannot do its job. */
    fun error(message: String): String = JSONObject().put("error", message).toString()
}
