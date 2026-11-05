package com.mdc159.sierra.voice.duplex

import com.mdc159.sierra.net.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ElevenLabs Agents (conversational AI) over websocket. We feed our own PCM and play
 * the agent's audio ourselves; turn detection, the LLM and the voice run on ElevenLabs.
 *
 * Message shapes follow the vendor's generated AsyncAPI types in the official JS SDK
 * (`elevenlabs/packages`, `packages/types/generated/types/asyncapi-types.ts`) and the
 * Python SDK's `conversational_ai/conversation.py`:
 *
 * - connect to `wss://api.elevenlabs.io/v1/convai/conversation?agent_id=...` for a public
 *   agent, or to the `signed_url` from `GET /v1/convai/conversation/get-signed-url`
 *   (header `xi-api-key`) for a private one;
 * - send `conversation_initiation_client_data`; the first reply is
 *   `conversation_initiation_metadata` with the audio formats (`pcm_16000` and so on);
 * - mic audio goes as `{"user_audio_chunk": "<base64>"}` (no `type` field);
 * - agent audio arrives as `audio` events with an `event_id`; an `interruption` event
 *   names the last `event_id` that still counts, everything at or below it is dropped;
 * - `ping` must be answered with `pong` carrying the same `event_id`;
 * - `client_tool_call` is answered with `client_tool_result`.
 *
 * What has to exist in the ElevenLabs account first is in docs/DUPLEX.md.
 */
class ElevenLabsAgentSession(
    private val config: DuplexConfig,
    private val tools: ToolRegistry,
    client: OkHttpClient,
    timing: WebSocketSessionBase.Timing = WebSocketSessionBase.Timing(),
) : WebSocketSessionBase(client, timing) {

    override val provider = DuplexProvider.ELEVENLABS

    @Volatile
    private var inRate: Int = DEFAULT_SAMPLE_RATE

    @Volatile
    private var outRate: Int = DEFAULT_SAMPLE_RATE

    override val inputSampleRate: Int get() = inRate
    override val outputSampleRate: Int get() = outRate

    /** The conversation id from the metadata event, for the ElevenLabs dashboard history. */
    @Volatile
    var conversationId: String? = null
        private set

    @Volatile
    private var lastInterruptEventId = -1L

    @Volatile
    private var lastServerInterruptAt = 0L

    /** Set by a tap interrupt: drop agent audio until the agent's next turn begins. */
    private val muteUntilNextTurn = AtomicBoolean(false)

    override fun buildRequest(): Request {
        val base = config.elevenBaseUrl.trim()
            .replaceFirst(Regex("^wss://"), "https://")
            .replaceFirst(Regex("^ws://"), "http://")
            .toHttpUrlOrNull() ?: throw DuplexException("The ElevenLabs URL is not valid.")
        val url = if (config.elevenApiKey.isBlank()) {
            base.newBuilder()
                .encodedPath("/v1/convai/conversation")
                .setQueryParameter("agent_id", config.elevenAgentId)
                .build()
        } else {
            fetchSignedUrl(base)
        }
        return Request.Builder().url(url).build()
    }

    /** Private agents: trade the API key for a short-lived signed websocket URL. */
    private fun fetchSignedUrl(base: HttpUrl): HttpUrl {
        val request = Request.Builder()
            .url(
                base.newBuilder()
                    .encodedPath("/v1/convai/conversation/get-signed-url")
                    .setQueryParameter("agent_id", config.elevenAgentId)
                    .build(),
            )
            .header("xi-api-key", config.elevenApiKey)
            .get()
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw DuplexException("ElevenLabs refused the sign-in (HTTP ${response.code}).")
                }
                text
            }
        } catch (e: DuplexException) {
            throw e
        } catch (e: IOException) {
            throw DuplexException(Http.describe(e), e)
        }
        val signed = try {
            JSONObject(body).optString("signed_url")
        } catch (e: Exception) {
            ""
        }
        val normalized = signed.replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
        return normalized.toHttpUrlOrNull() ?: throw DuplexException("ElevenLabs sent no usable signed URL.")
    }

    override fun onSocketOpen(socket: WebSocket) {
        socket.send(buildInitiation().toString())
    }

    /** The `conversation_initiation_client_data` message. Overrides need to be enabled per agent. */
    fun buildInitiation(): JSONObject {
        val agent = JSONObject()
        if (config.instructions.isNotBlank()) {
            agent.put("prompt", JSONObject().put("prompt", config.instructions))
        }
        val override = JSONObject().put("agent", agent)
        if (config.elevenVoiceId.isNotBlank()) {
            override.put("tts", JSONObject().put("voice_id", config.elevenVoiceId))
        }
        return JSONObject()
            .put("type", "conversation_initiation_client_data")
            .put("conversation_config_override", override)
            .put("source_info", JSONObject().put("source", "sierra_auto").put("version", "0.1"))
    }

    override fun onBeforeReconnect() {
        lastInterruptEventId = -1
        muteUntilNextTurn.set(false)
    }

    override fun onJson(message: JSONObject) {
        when (message.optString("type")) {
            "conversation_initiation_metadata" -> {
                val meta = message.optJSONObject("conversation_initiation_metadata_event") ?: JSONObject()
                conversationId = meta.optString("conversation_id").ifBlank { null }
                val out = parseFormat(meta.optString("agent_output_audio_format", "pcm_16000"))
                val inp = parseFormat(meta.optString("user_input_audio_format", "pcm_16000"))
                if (out == null || inp == null) {
                    handshakeFailed("The agent uses an audio format Sierra cannot play. Set PCM in the ElevenLabs dashboard.")
                    return
                }
                outRate = out
                inRate = inp
                handshakeComplete()
            }
            "audio" -> {
                val ev = message.optJSONObject("audio_event") ?: return
                val eventId = ev.optLong("event_id", Long.MAX_VALUE)
                if (eventId <= lastInterruptEventId) return
                if (muteUntilNextTurn.get()) return
                val pcm = Base64.getDecoder().decode(ev.optString("audio_base_64"))
                emit(DuplexEvent.AssistantAudio(pcm, outputSampleRate, null))
            }
            "user_transcript" -> {
                muteUntilNextTurn.set(false)
                val text = message.optJSONObject("user_transcription_event")?.optString("user_transcript").orEmpty()
                emit(DuplexEvent.UserTranscript(text.trim(), final = true))
            }
            "internal_tentative_agent_response" -> {
                val text = message.optJSONObject("tentative_agent_response_internal_event")
                    ?.optString("tentative_agent_response").orEmpty()
                emit(DuplexEvent.AssistantTranscript(text.trim(), final = false))
            }
            "agent_response" -> {
                muteUntilNextTurn.set(false)
                val text = message.optJSONObject("agent_response_event")?.optString("agent_response").orEmpty()
                emit(DuplexEvent.AssistantTranscript(text.trim(), final = true))
            }
            "agent_response_correction" -> {
                val text = message.optJSONObject("agent_response_correction_event")
                    ?.optString("corrected_agent_response").orEmpty()
                emit(DuplexEvent.AssistantTranscript(text.trim(), final = true))
            }
            "interruption" -> {
                lastInterruptEventId = message.optJSONObject("interruption_event")?.optLong("event_id", -1) ?: -1
                lastServerInterruptAt = System.currentTimeMillis()
                emit(DuplexEvent.UserStartedSpeaking)
                emit(DuplexEvent.AssistantDone(cancelled = true))
            }
            "ping" -> {
                val id = message.optJSONObject("ping_event")?.opt("event_id")
                send(JSONObject().put("type", "pong").put("event_id", id ?: 0))
            }
            "client_tool_call" -> {
                val call = message.optJSONObject("client_tool_call") ?: return
                val callId = call.optString("tool_call_id")
                val name = call.optString("tool_name")
                val args = (call.optJSONObject("parameters") ?: JSONObject()).toString()
                emit(DuplexEvent.ToolCall(callId, name, args))
                toolExecutor.execute { runTool(callId, name, args) }
            }
            "error" -> {
                val err = message.optJSONObject("error_event")
                val text = err?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: message.optString("message").takeIf { it.isNotBlank() }
                    ?: "ElevenLabs reported an error."
                if (isHandshaking) handshakeFailed(text) else emit(DuplexEvent.Error(text, fatal = false))
            }
            else -> Unit // vad_score, asr metadata, mcp events: not needed.
        }
    }

    private fun runTool(callId: String, name: String, args: String) {
        val outcome = tools.run(name, args)
        emit(DuplexEvent.ToolResult(callId, name, outcome.result, outcome.isError))
        if (isClosed) return
        send(
            JSONObject()
                .put("type", "client_tool_result")
                .put("tool_call_id", callId)
                .put("result", outcome.result)
                .put("is_error", outcome.isError),
        )
    }

    override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || isClosed) return
        val b64 = Base64.getEncoder().encodeToString(
            if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length),
        )
        send(JSONObject().put("user_audio_chunk", b64))
    }

    /**
     * ElevenLabs has no client message that stops the agent mid-sentence; its own VAD does
     * that from the audio we send. A tap therefore mutes the rest of this reply locally
     * until the agent's next turn.
     */
    override fun interrupt(playedMs: Long) {
        // Right after a server-side interruption the event-id filter already does the job.
        if (System.currentTimeMillis() - lastServerInterruptAt < 2_000) return
        muteUntilNextTurn.set(true)
        emit(DuplexEvent.AssistantDone(cancelled = true))
    }

    override fun sendText(text: String) {
        if (text.isBlank() || isClosed) return
        send(JSONObject().put("type", "user_message").put("text", text))
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000

        /** `pcm_16000` → 16000. Returns null for formats we cannot play (µ-law). */
        fun parseFormat(format: String): Int? {
            val parts = format.trim().split('_')
            if (parts.size != 2 || parts[0] != "pcm") return null
            return parts[1].toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
