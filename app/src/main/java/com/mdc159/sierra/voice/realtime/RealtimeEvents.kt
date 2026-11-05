package com.mdc159.sierra.voice.realtime

import com.mdc159.sierra.agent.Conversation
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Settings for one realtime (speech-to-speech) session through the gateway.
 *
 * @property baseUrl the gateway base, for example `http://host:4000/v1`; converted to ws(s).
 * @property legacyEvents use the 2024 beta event names (`input_audio_format: "pcm16"`,
 *   `response.audio.delta`) instead of the GA names (`audio.input.format`,
 *   `response.output_audio.delta`). Incoming events of both dialects are always understood.
 */
data class RealtimeConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    val voice: String = DEFAULT_VOICE,
    val instructions: String = Conversation.SYSTEM_PROMPT,
    val inputSampleRate: Int = 16_000,
    val wireSampleRate: Int = 24_000,
    /** Transcription model for the driver's words, or blank for none. */
    val transcriptionModel: String = "gpt-4o-mini-transcribe",
    val legacyEvents: Boolean = false,
) {
    /** `wss://host/v1/realtime?model=…` (or `ws://` for a plain-http Tailscale gateway). */
    fun websocketUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val withV1 = if (Regex(".*/v\\d+[a-z]*$").matches(trimmed)) trimmed else "$trimmed/v1"
        val ws = when {
            withV1.startsWith("https://") -> "wss://" + withV1.removePrefix("https://")
            withV1.startsWith("http://") -> "ws://" + withV1.removePrefix("http://")
            else -> withV1
        }
        return "$ws/realtime?model=${java.net.URLEncoder.encode(model, "UTF-8")}"
    }

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-realtime-2.1"
        const val DEFAULT_VOICE = "marin"
    }
}

/** Encodes client events and decodes server events of the OpenAI Realtime protocol. */
object RealtimeEvents {

    sealed class Incoming {
        data class SessionReady(val type: String) : Incoming()
        class AudioDelta(val pcm: ByteArray) : Incoming()
        data object AudioDone : Incoming()
        data class AssistantTextDelta(val text: String) : Incoming()
        data class UserTranscript(val text: String) : Incoming()
        data object SpeechStarted : Incoming()
        data object SpeechStopped : Incoming()
        data object ResponseDone : Incoming()
        data class Error(val message: String) : Incoming()
        data class Unknown(val type: String) : Incoming()
    }

    fun sessionUpdate(c: RealtimeConfig): String {
        val session = JSONObject().put("instructions", c.instructions)
        if (c.legacyEvents) {
            session.put("modalities", org.json.JSONArray().put("audio").put("text"))
                .put("voice", c.voice)
                .put("input_audio_format", "pcm16")
                .put("output_audio_format", "pcm16")
                .put("turn_detection", JSONObject().put("type", "server_vad"))
            if (c.transcriptionModel.isNotBlank()) {
                session.put("input_audio_transcription", JSONObject().put("model", c.transcriptionModel))
            }
        } else {
            val input = JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", c.wireSampleRate))
                .put("turn_detection", JSONObject().put("type", "server_vad"))
            if (c.transcriptionModel.isNotBlank()) {
                input.put("transcription", JSONObject().put("model", c.transcriptionModel))
            }
            val output = JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", c.wireSampleRate))
                .put("voice", c.voice)
            session.put("type", "realtime")
                .put("output_modalities", org.json.JSONArray().put("audio"))
                .put("audio", JSONObject().put("input", input).put("output", output))
        }
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun audioAppend(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size): String {
        val slice = if (offset == 0 && length == pcm.size) pcm else pcm.copyOfRange(offset, offset + length)
        return JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.getEncoder().encodeToString(slice))
            .toString()
    }

    fun audioCommit(): String = JSONObject().put("type", "input_audio_buffer.commit").toString()
    fun audioClear(): String = JSONObject().put("type", "input_audio_buffer.clear").toString()
    fun responseCreate(): String = JSONObject().put("type", "response.create").toString()
    fun responseCancel(): String = JSONObject().put("type", "response.cancel").toString()

    fun parse(json: String): Incoming {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return Incoming.Error("Unreadable event from the realtime server.")
        }
        val type = o.optString("type")
        return when (type) {
            "session.created", "session.updated" -> Incoming.SessionReady(type)
            "response.audio.delta", "response.output_audio.delta" -> {
                val delta = o.optString("delta")
                val bytes = try {
                    Base64.getDecoder().decode(delta)
                } catch (e: IllegalArgumentException) {
                    return Incoming.Error("Bad audio in a realtime event.")
                }
                Incoming.AudioDelta(bytes)
            }
            "response.audio.done", "response.output_audio.done" -> Incoming.AudioDone
            "response.audio_transcript.delta", "response.output_audio_transcript.delta", "response.text.delta", "response.output_text.delta" ->
                Incoming.AssistantTextDelta(o.optString("delta"))
            "conversation.item.input_audio_transcription.completed" -> Incoming.UserTranscript(o.optString("transcript").trim())
            "input_audio_buffer.speech_started" -> Incoming.SpeechStarted
            "input_audio_buffer.speech_stopped" -> Incoming.SpeechStopped
            "response.done" -> {
                val status = o.optJSONObject("response")?.optString("status").orEmpty()
                if (status == "failed") {
                    val detail = o.optJSONObject("response")?.optJSONObject("status_details")?.optJSONObject("error")?.optString("message")
                    Incoming.Error(detail?.takeIf { it.isNotBlank() } ?: "The realtime model failed to answer.")
                } else {
                    Incoming.ResponseDone
                }
            }
            "error" -> {
                val err = o.optJSONObject("error")
                Incoming.Error(err?.optString("message")?.takeIf { it.isNotBlank() } ?: "Realtime error.")
            }
            else -> Incoming.Unknown(type)
        }
    }
}

/** Linear-interpolation resampler for 16-bit mono PCM (16 kHz car mic → 24 kHz wire). */
object Resampler {
    fun resample(input: ByteArray, length: Int, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return input.copyOfRange(0, length)
        val inSamples = length / 2
        if (inSamples == 0) return ByteArray(0)
        val outSamples = (inSamples.toLong() * toRate / fromRate).toInt()
        val out = ByteArray(outSamples * 2)
        for (i in 0 until outSamples) {
            val pos = i.toDouble() * fromRate / toRate
            val idx = pos.toInt().coerceAtMost(inSamples - 1)
            val frac = pos - idx
            val a = sample(input, idx)
            val b = sample(input, (idx + 1).coerceAtMost(inSamples - 1))
            val v = (a + (b - a) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sample(b: ByteArray, i: Int): Double =
        ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xFF)).toShort().toDouble()
}
