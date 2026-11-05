package com.mdc159.sierra.voice.tts

import com.mdc159.sierra.agent.AgentConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Builds the HTTP request that turns one chunk of text into audio. The reply body must be
 * 16-bit little-endian mono PCM, either raw at [sampleRate] Hz or wrapped in a WAV header
 * (which [StreamedSpeechOutput] detects and strips, taking the rate from the header).
 */
interface TtsRequestFactory {
    val name: String

    /** Sample rate of a raw (header-less) reply. */
    val sampleRate: Int

    fun build(text: String): Request
}

/**
 * The owner's LiteLLM gateway: OpenAI-compatible `POST /v1/audio/speech`. Asks for
 * `response_format: "pcm"`, which OpenAI documents as raw 24 kHz 16-bit mono PCM, so playback
 * can start on the first bytes without decoding. Whether LiteLLM passes `pcm` through
 * unchanged for every upstream is unverified; a WAV reply is handled too.
 */
class GatewayTts(
    baseUrl: String,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val voice: String = DEFAULT_VOICE,
    private val speed: Double = 1.0,
    private val instructions: String = "",
) : TtsRequestFactory {

    private val url = AgentConfig.endpoint(baseUrl, "audio/speech")

    override val name: String get() = "Gateway ($model, $voice)"
    override val sampleRate: Int get() = 24_000

    override fun build(text: String): Request {
        val body = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", voice)
            .put("response_format", "pcm")
        if (speed != 1.0) body.put("speed", speed)
        if (instructions.isNotBlank()) body.put("instructions", instructions)
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "audio/pcm, audio/wav, */*")
            .post(body.toString().toRequestBody(JSON))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-4o-mini-tts"
        const val DEFAULT_VOICE = "coral"

        /** gpt-4o-mini-tts voices as listed by OpenAI (unverified on the gateway). */
        val VOICES = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * ElevenLabs streaming text-to-speech: `POST /v1/text-to-speech/{voice}/stream` with
 * `output_format=pcm_24000` (raw 24 kHz 16-bit mono). The key goes in the `xi-api-key`
 * header. Availability of PCM output on the owner's plan is unverified.
 */
class ElevenLabsTts(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : TtsRequestFactory {

    override val name: String get() = "ElevenLabs ($modelId)"
    override val sampleRate: Int get() = 24_000

    override fun build(text: String): Request {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", modelId)
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/text-to-speech/$voiceId/stream?output_format=pcm_24000")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/pcm, */*")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.elevenlabs.io"
        const val DEFAULT_MODEL = "eleven_flash_v2_5"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
