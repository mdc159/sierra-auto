package com.mdc159.sierra.voice.duplex

import com.mdc159.sierra.agent.AgentConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI Realtime protocol (the GA websocket API) against OpenAI or any compatible
 * gateway. Server-side turn detection, function calling, and client-side truncation
 * of the assistant's audio when the driver talks over it.
 *
 * Event names and fields follow the OpenAI Node SDK's realtime types
 * (`openai/openai-node`, `src/resources/realtime/realtime.ts`), which is the vendor's
 * own definition of the wire protocol. The older beta names (`response.audio.delta`,
 * `response.audio_transcript.delta`) are also accepted so a gateway pinned to the beta
 * protocol still works. Audio is 16-bit PCM, mono, 24 kHz ("only a 24kHz sample rate is
 * supported" for `audio/pcm`), base64 in JSON text frames.
 */
class OpenAiRealtimeSession(
    private val config: DuplexConfig,
    private val tools: ToolRegistry,
    client: OkHttpClient,
    timing: WebSocketSessionBase.Timing = WebSocketSessionBase.Timing(),
) : WebSocketSessionBase(client, timing) {

    override val provider = DuplexProvider.OPENAI_REALTIME
    override val inputSampleRate = SAMPLE_RATE
    override val outputSampleRate = SAMPLE_RATE

    private val sessionCreated = AtomicBoolean(false)

    // Per-response state, touched only from the socket thread (plus interrupt()).
    @Volatile
    private var activeResponseId: String? = null

    @Volatile
    private var cancelledByServer = false

    @Volatile
    private var audioItemId: String? = null

    @Volatile
    private var audioContentIndex = 0

    @Volatile
    private var audioReceivedBytes = 0L

    @Volatile
    private var truncatedItemId: String? = null

    private val userTranscripts = HashMap<String, StringBuilder>()
    private val assistantTranscript = StringBuilder()

    override fun buildRequest(): Request {
        val url = try {
            AgentConfig.endpoint(config.openAiBaseUrl, "realtime")
                .toHttpUrlOrThrow()
                .newBuilder()
                .setQueryParameter("model", config.openAiModel)
                .build()
        } catch (e: IllegalArgumentException) {
            throw DuplexException("The realtime base URL is not valid.", e)
        }
        val builder = Request.Builder().url(url)
        if (config.openAiApiKey.isNotBlank()) builder.header("Authorization", "Bearer ${config.openAiApiKey}")
        return builder.build()
    }

    override fun onSocketOpen(socket: WebSocket) {
        socket.send(buildSessionUpdate().toString())
    }

    /** The `session.update` event that configures audio, turn detection and tools. */
    fun buildSessionUpdate(): JSONObject {
        val pcm = JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE)
        val input = JSONObject()
            .put("format", pcm)
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("threshold", 0.5)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 500)
                    .put("create_response", true)
                    .put("interrupt_response", true),
            )
        if (config.openAiTranscriptionModel.isNotBlank()) {
            input.put("transcription", JSONObject().put("model", config.openAiTranscriptionModel))
        }
        val output = JSONObject().put("format", pcm)
        if (config.openAiVoice.isNotBlank()) output.put("voice", config.openAiVoice)
        val session = JSONObject()
            .put("type", "realtime")
            .put("output_modalities", JSONArray().put("audio"))
            .put("instructions", config.instructions)
            .put("audio", JSONObject().put("input", input).put("output", output))
        if (tools.all.isNotEmpty()) {
            val arr = JSONArray()
            tools.all.forEach {
                arr.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", it.name)
                        .put("description", it.description)
                        .put("parameters", it.parameters),
                )
            }
            session.put("tools", arr).put("tool_choice", "auto")
        }
        return JSONObject().put("type", "session.update").put("session", session)
    }

    override fun onHandshakeTimeout(): Boolean {
        if (!sessionCreated.get()) return false
        emit(DuplexEvent.Error("The gateway did not confirm the session settings; continuing.", fatal = false))
        return true
    }

    override fun onBeforeReconnect() {
        sessionCreated.set(false)
        activeResponseId = null
        cancelledByServer = false
        audioItemId = null
        audioReceivedBytes = 0
        truncatedItemId = null
        assistantTranscript.setLength(0)
    }

    override fun onJson(message: JSONObject) {
        when (val type = message.optString("type")) {
            "session.created" -> sessionCreated.set(true)
            "session.updated" -> handshakeComplete()
            "error" -> {
                val err = message.optJSONObject("error")
                val text = err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Voice service error."
                if (isHandshaking) handshakeFailed(text) else emit(DuplexEvent.Error(text, fatal = false))
            }
            "input_audio_buffer.speech_started" -> {
                // With interrupt_response=true the server cancels the response itself.
                if (activeResponseId != null) cancelledByServer = true
                emit(DuplexEvent.UserStartedSpeaking)
            }
            "input_audio_buffer.speech_stopped" -> emit(DuplexEvent.UserStoppedSpeaking)
            "conversation.item.input_audio_transcription.delta" -> {
                val id = message.optString("item_id")
                val sb = userTranscripts.getOrPut(id) { StringBuilder() }
                sb.append(message.optString("delta"))
                emit(DuplexEvent.UserTranscript(sb.toString(), final = false))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                userTranscripts.remove(message.optString("item_id"))
                emit(DuplexEvent.UserTranscript(message.optString("transcript").trim(), final = true))
            }
            "response.created" -> {
                activeResponseId = message.optJSONObject("response")?.optString("id")?.ifBlank { null } ?: "?"
                cancelledByServer = false
                audioItemId = null
                audioReceivedBytes = 0
                assistantTranscript.setLength(0)
            }
            "response.output_audio.delta", "response.audio.delta" -> {
                val pcm = Base64.getDecoder().decode(message.optString("delta"))
                val itemId = message.optString("item_id").ifBlank { null }
                if (itemId != audioItemId) {
                    audioItemId = itemId
                    audioReceivedBytes = 0
                    audioContentIndex = message.optInt("content_index", 0)
                }
                audioReceivedBytes += pcm.size
                emit(DuplexEvent.AssistantAudio(pcm, SAMPLE_RATE, itemId))
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                assistantTranscript.append(message.optString("delta"))
                emit(DuplexEvent.AssistantTranscript(assistantTranscript.toString(), final = false))
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> {
                emit(DuplexEvent.AssistantTranscript(message.optString("transcript").trim(), final = true))
            }
            "response.function_call_arguments.done" -> {
                val callId = message.optString("call_id")
                val name = message.optString("name")
                val args = message.optString("arguments")
                emit(DuplexEvent.ToolCall(callId, name, args))
                toolExecutor.execute { runTool(callId, name, args) }
            }
            "response.done" -> {
                val response = message.optJSONObject("response")
                val status = response?.optString("status").orEmpty()
                activeResponseId = null
                emit(DuplexEvent.AssistantDone(cancelled = status == "cancelled"))
            }
            else -> Unit // session.updated-adjacent chatter, rate limits, item lifecycle: not needed.
        }
    }

    private fun runTool(callId: String, name: String, args: String) {
        val outcome = tools.run(name, args)
        emit(DuplexEvent.ToolResult(callId, name, outcome.result, outcome.isError))
        if (isClosed) return
        val output = if (outcome.isError) "Error: ${outcome.result}" else outcome.result
        send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", callId)
                        .put("output", output),
                ),
        )
        send(JSONObject().put("type", "response.create"))
    }

    override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || isClosed) return
        val b64 = Base64.getEncoder().encodeToString(
            if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length),
        )
        send(JSONObject().put("type", "input_audio_buffer.append").put("audio", b64))
    }

    /**
     * Stops the current reply. If the server already cancelled it (speech detected), only
     * the truncate is sent, so the transcript the model keeps matches what the driver heard.
     * `audio_end_ms` is clamped to the audio actually received, which the server rejects
     * otherwise.
     */
    override fun interrupt(playedMs: Long) {
        if (isClosed) return
        if (activeResponseId != null && !cancelledByServer) {
            send(JSONObject().put("type", "response.cancel"))
            cancelledByServer = true
        }
        val itemId = audioItemId ?: return
        if (itemId == truncatedItemId) return
        truncatedItemId = itemId
        val receivedMs = Resampler.durationMs(audioReceivedBytes.toInt(), SAMPLE_RATE)
        send(
            JSONObject()
                .put("type", "conversation.item.truncate")
                .put("item_id", itemId)
                .put("content_index", audioContentIndex)
                .put("audio_end_ms", playedMs.coerceIn(0, receivedMs)),
        )
    }

    override fun sendText(text: String) {
        if (text.isBlank() || isClosed) return
        send(
            JSONObject()
                .put("type", "conversation.item.create")
                .put(
                    "item",
                    JSONObject()
                        .put("type", "message")
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "input_text").put("text", text)),
                        ),
                ),
        )
        send(JSONObject().put("type", "response.create"))
    }

    companion object {
        const val SAMPLE_RATE = 24_000

        private fun String.toHttpUrlOrThrow(): HttpUrl {
            // OkHttp speaks websockets over http(s) URLs; map ws(s) schemes for convenience.
            val normalized = replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
            return normalized.toHttpUrlOrNull() ?: throw IllegalArgumentException("bad url")
        }
    }
}
