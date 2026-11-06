package com.mdc159.sierra.agent

import com.mdc159.sierra.agent.tools.Tool
import com.mdc159.sierra.agent.tools.ToolException
import com.mdc159.sierra.agent.tools.ToolSchema
import com.mdc159.sierra.net.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One function call the model asked for. [arguments] is the raw JSON object text. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * A chat message in the OpenAI format. Plain user, system and assistant messages use
 * only [role] and [content]. An assistant turn that calls tools carries [toolCalls];
 * each tool's result is a `tool` message carrying the matching [toolCallId].
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** Thrown with a short message that can go straight onto the car screen. */
class AgentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Lets the car screen show what the agent is doing while a tool runs. Called off the main thread. */
interface ToolListener {
    fun onToolStart(status: String)
    fun onToolEnd()
}

/**
 * Minimal OpenAI-compatible client: chat completions (with optional tool calling) and
 * audio transcriptions.
 *
 * Tool calling follows the `tools` / `tool_calls` protocol: the model may answer a chat
 * request with function calls; the client runs them and sends the results back, for at
 * most [maxRounds] model requests, each bounded by [roundTimeoutMs]. A tool run is
 * bounded by [toolTimeoutMs]. On top of that, one whole exchange may take at most
 * [exchangeBudgetMs] from its first request: every round and tool is trimmed to the time
 * left, and when it is spent the exchange ends with "Took too long", because a driver
 * will not wait through four rounds and three tools at their individual limits. The last
 * round never offers tools, so the driver always gets a spoken answer. If the endpoint
 * rejects the `tools` field, the request is retried as plain chat and tools stay off for
 * the rest of this client's life.
 */
class AgentClient(
    private val config: AgentConfig,
    private val client: OkHttpClient = Http.client,
    private val maxRounds: Int = MAX_ROUNDS,
    private val roundTimeoutMs: Long = if (config.usesBridge) BRIDGE_ROUND_TIMEOUT_MS else ROUND_TIMEOUT_MS,
    private val toolTimeoutMs: Long = TOOL_TIMEOUT_MS,
    private val exchangeBudgetMs: Long = if (config.usesBridge) BRIDGE_EXCHANGE_BUDGET_MS else EXCHANGE_BUDGET_MS,
    /** Monotonic milliseconds; injectable so tests can spend the budget without waiting. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** Chat through the bridge may wait long for the first byte; transcription keeps [client]'s bounds. */
    private val chatClient: OkHttpClient = if (config.usesBridge && client === Http.client) Http.slowClient else client

    @Volatile
    private var toolsSupported = true

    /** Whether the endpoint has accepted the `tools` field so far (true until it refuses). */
    val supportsTools: Boolean get() = toolsSupported

    fun chat(messages: List<ChatMessage>): String = chat(messages, emptyList(), null)

    /**
     * Sends [messages] and returns the model's final text reply, running any [tools] it
     * asks for along the way. [listener] hears when each tool starts and ends.
     */
    fun chat(messages: List<ChatMessage>, tools: List<Tool>, listener: ToolListener?): String {
        val history = messages.toMutableList()
        val byName = tools.associateBy { it.name }
        var offerTools = tools.isNotEmpty() && toolsSupported
        val rounds = maxRounds.coerceAtLeast(1)
        val deadline = Deadline(clock, exchangeBudgetMs)
        for (round in 1..rounds) {
            // The final round is plain chat so the loop always ends in words for the driver.
            val withTools = offerTools && round < rounds
            val reply = try {
                request(history, if (withTools) tools else emptyList(), deadline.slice(roundTimeoutMs))
            } catch (e: AgentException) {
                if (!withTools || !rejectsTools(e)) throw e
                toolsSupported = false
                offerTools = false
                request(history, emptyList(), deadline.slice(roundTimeoutMs))
            }
            if (reply.toolCalls.isEmpty() || !withTools) {
                val content = reply.content.trim()
                if (content.isEmpty()) throw AgentException("The agent sent an empty reply.")
                return content
            }
            history += reply
            for (call in reply.toolCalls) {
                val result = runTool(byName[call.name], call, listener, deadline.slice(toolTimeoutMs))
                history += ChatMessage("tool", result, toolCallId = call.id)
            }
        }
        throw AgentException("The agent didn't finish.") // unreachable: the last round returns or throws
    }

    /** The time left in one exchange. [slice] trims a step's own timeout to what remains. */
    private class Deadline(private val clock: () -> Long, budgetMs: Long) {
        private val endsAt = clock() + budgetMs

        fun slice(stepTimeoutMs: Long): Long {
            val remaining = endsAt - clock()
            if (remaining <= 0) throw AgentException(TOO_LONG)
            return if (stepTimeoutMs > 0) minOf(stepTimeoutMs, remaining) else remaining
        }
    }

    private fun request(messages: List<ChatMessage>, tools: List<Tool>, timeoutMs: Long): ChatMessage {
        val body = JSONObject()
            .put("model", config.chatModel)
            .put("messages", JSONArray().apply { messages.forEach { put(encode(it)) } })
        if (tools.isNotEmpty()) body.put("tools", JSONArray().apply { tools.forEach { put(it.spec()) } })
        val request = Request.Builder()
            .url(config.chatCompletionsUrl())
            .withAuth(config.chatKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = execute(request, "Agent", timeoutMs, chatClient)
        try {
            val message = JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return decode(message)
        } catch (e: JSONException) {
            throw AgentException("The agent sent a reply Sierra couldn't read.", e)
        }
    }

    /** Runs one tool off this thread so a hung tool cannot hang the exchange. Never throws for tool failures. */
    private fun runTool(tool: Tool?, call: ToolCall, listener: ToolListener?, timeoutMs: Long): String {
        if (tool == null) return ToolSchema.error("Unknown tool ${call.name}. Answer without it.")
        val args = parseArguments(call.arguments)
            ?: return ToolSchema.error("The arguments for ${call.name} were not a JSON object.")
        listener?.onToolStart(tool.status(args))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> { tool.run(args) }
            return try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                ToolSchema.error("${call.name} timed out. Tell the driver it took too long.")
            } catch (e: ExecutionException) {
                when (val cause = e.cause) {
                    is ToolException -> ToolSchema.error(cause.message ?: "The tool failed.")
                    else -> ToolSchema.error("${call.name} failed: ${Http.describe(cause ?: e)}")
                }
            }
        } finally {
            executor.shutdownNow()
            listener?.onToolEnd()
        }
    }

    fun transcribe(wav: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", config.sttModel)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            .build()
        val request = Request.Builder()
            .url(config.transcriptionsUrl())
            .withAuth(config.effectiveSttKey)
            .post(body)
            .build()
        val json = execute(request, "Transcription")
        return try {
            JSONObject(json).optString("text").trim()
        } catch (e: JSONException) {
            throw AgentException("Transcription reply couldn't be read.", e)
        }
    }

    /** Cheap reachability and auth check for the phone's "Test connection" button. */
    fun testConnection(): String {
        val reply = chat(listOf(ChatMessage("user", "Reply with the single word: ready")))
        return reply.take(60)
    }

    private fun Request.Builder.withAuth(key: String): Request.Builder =
        if (key.isBlank()) this else header("Authorization", "Bearer $key")

    private fun execute(request: Request, what: String, timeoutMs: Long = 0, client: OkHttpClient = this.client): String {
        val response: Response = try {
            val call = client.newCall(request)
            if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            call.execute()
        } catch (e: IOException) {
            throw AgentException(Http.describe(e), e)
        } catch (e: IllegalArgumentException) {
            throw AgentException("$what URL is not valid.", e)
        }
        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw AgentException(Http.describe(e), e)
            }
            if (!it.isSuccessful) throw AgentException("$what error ${it.code}: ${errorDetail(text)}".trimEnd(' ', ':'), HttpStatus(it.code))
            return text
        }
    }

    /** Carries the HTTP status on an [AgentException] so the tools fallback can look at it. */
    private class HttpStatus(val code: Int) : Exception("HTTP $code")

    companion object {
        const val MAX_ROUNDS = 4
        const val ROUND_TIMEOUT_MS = 30_000L
        const val TOOL_TIMEOUT_MS = 30_000L
        const val EXCHANGE_BUDGET_MS = 60_000L

        /**
         * The bridge profile: an agent such as Hermes may use its own tools before answering.
         * Set the bridge's own `timeout_seconds` below the round limit (110 s on victoria), so
         * the bridge answers with its 504 before the phone gives up.
         */
        const val BRIDGE_ROUND_TIMEOUT_MS = 120_000L
        const val BRIDGE_EXCHANGE_BUDGET_MS = 140_000L
        const val TOO_LONG = "Took too long. Try again."
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WAV = "audio/wav".toMediaType()

        /**
         * An endpoint that doesn't know the `tools` field answers 400 or 422 (OpenAI style
         * validation) or, on some gateways, 404 for the model in tools mode. Anything else
         * is a real failure and is not retried.
         */
        fun rejectsTools(e: AgentException): Boolean =
            (e.cause as? HttpStatus)?.code in setOf(400, 404, 422)

        /** Pulls `error.message` out of an OpenAI-style error body, if there is one. */
        fun errorDetail(body: String): String {
            val detail = try {
                val obj = JSONObject(body)
                val err = obj.opt("error")
                when (err) {
                    is JSONObject -> err.optString("message")
                    is String -> err
                    else -> obj.optString("message").ifBlank { obj.optString("detail") }
                }
            } catch (e: JSONException) {
                body
            }
            return detail.replace(Regex("\\s+"), " ").trim().take(100)
        }

        /** The OpenAI wire form of a message. Assistant tool calls carry `null` content when they have no text. */
        fun encode(message: ChatMessage): JSONObject {
            val o = JSONObject().put("role", message.role)
            if (message.toolCalls.isEmpty()) {
                o.put("content", message.content)
            } else {
                o.put("content", if (message.content.isBlank()) JSONObject.NULL else message.content)
                o.put(
                    "tool_calls",
                    JSONArray().apply {
                        message.toolCalls.forEach { call ->
                            put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("type", "function")
                                    .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments)),
                            )
                        }
                    },
                )
            }
            message.toolCallId?.let { o.put("tool_call_id", it) }
            return o
        }

        /**
         * Reads a `choices[0].message`. Content may be JSON `null` when the model only calls
         * tools (Android's org.json would stringify that as "null", so it is checked first).
         * Tool call ids are optional on some servers and are filled in when missing;
         * arguments may arrive as a JSON string or already as an object.
         */
        fun decode(message: JSONObject): ChatMessage {
            val content = if (message.isNull("content")) "" else message.optString("content")
            val calls = mutableListOf<ToolCall>()
            val array = message.optJSONArray("tool_calls")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val call = array.optJSONObject(i) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name").trim()
                    if (name.isEmpty()) continue
                    val rawArgs = function.opt("arguments")
                    val arguments = when (rawArgs) {
                        null, JSONObject.NULL -> "{}"
                        is JSONObject -> rawArgs.toString()
                        else -> rawArgs.toString().ifBlank { "{}" }
                    }
                    val id = call.optString("id").ifBlank { "call_$i" }
                    calls += ToolCall(id, name, arguments)
                }
            }
            return ChatMessage("assistant", content, calls)
        }

        fun parseArguments(raw: String): JSONObject? = try {
            JSONObject(raw.ifBlank { "{}" })
        } catch (e: JSONException) {
            null
        }
    }
}
