package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.agent.AgentClient
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.voice.Pcm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Turns one utterance of PCM into text. Returns "" when nothing intelligible was said. */
interface Transcriber {
    suspend fun transcribe(pcm: ByteArray, sampleRate: Int): String
}

/**
 * OpenAI-compatible `POST /v1/audio/transcriptions` on the owner's gateway, with the model
 * chosen in voice settings (for example `groq/whisper-large-v3-turbo` or
 * `openai/gpt-4o-transcribe`). Throws [AgentException] with a screen-ready message.
 */
class GatewayTranscriber(
    baseUrl: String,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val language: String = "",
    private val http: OkHttpClient = Http.client,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Transcriber {

    private val url = AgentConfig.endpoint(baseUrl, "audio/transcriptions")

    override suspend fun transcribe(pcm: ByteArray, sampleRate: Int): String =
        runInterruptible(ioDispatcher) { transcribeBlocking(pcm, sampleRate) }

    fun transcribeBlocking(pcm: ByteArray, sampleRate: Int): String {
        val wav = Pcm.toWav(pcm, sampleRate)
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            .build()
        val builder = Request.Builder().url(url).post(form)
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw AgentException(Http.describe(e), e)
        } catch (e: IllegalArgumentException) {
            throw AgentException("Transcription URL is not valid.", e)
        }
        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw AgentException(Http.describe(e), e)
            }
            if (!it.isSuccessful) {
                throw AgentException("Transcription error ${it.code}: ${AgentClient.errorDetail(text)}".trimEnd(' ', ':'))
            }
            return try {
                JSONObject(text).optString("text").trim()
            } catch (e: JSONException) {
                throw AgentException("Transcription reply couldn't be read.", e)
            }
        }
    }

    companion object {
        /** Measured on the owner's gateway (issue #39): 0.7–1.1 s, no word errors; the recommended no-ElevenLabs default. */
        const val DEFAULT_MODEL = "openai/gpt-4o-mini-transcribe"

        /** Models measured working on the owner's gateway on 2026-11-04 (issue #39), plus the OpenAI default name. */
        val KNOWN_MODELS = listOf("openai/gpt-4o-mini-transcribe", "groq/whisper-large-v3-turbo", "openai/gpt-4o-transcribe", "groq/whisper-large-v3", "whisper-1")
        private val WAV = "audio/wav".toMediaType()
    }
}

/**
 * ElevenLabs speech-to-text: `POST /v1/speech-to-text`, multipart `model_id` and `file`,
 * header `xi-api-key`. Measured at 0.36–0.84 s on the owner's account with `scribe_v2`
 * (issue #39), the fastest transcription available to Sierra tonight.
 */
class ElevenLabsTranscriber(
    private val apiKey: String,
    private val modelId: String = DEFAULT_MODEL,
    private val languageCode: String = "",
    private val baseUrl: String = "https://api.elevenlabs.io",
    private val http: OkHttpClient = Http.client,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Transcriber {

    override suspend fun transcribe(pcm: ByteArray, sampleRate: Int): String =
        runInterruptible(ioDispatcher) { transcribeBlocking(pcm, sampleRate) }

    fun transcribeBlocking(pcm: ByteArray, sampleRate: Int): String {
        val wav = Pcm.toWav(pcm, sampleRate)
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model_id", modelId)
            .apply { if (languageCode.isNotBlank()) addFormDataPart("language_code", languageCode) }
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            .build()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(form)
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AgentException(Http.describe(e), e)
        }
        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw AgentException(Http.describe(e), e)
            }
            if (!it.isSuccessful) {
                throw AgentException("Transcription error ${it.code}: ${AgentClient.errorDetail(text)}".trimEnd(' ', ':'))
            }
            return try {
                JSONObject(text).optString("text").trim()
            } catch (e: JSONException) {
                throw AgentException("Transcription reply couldn't be read.", e)
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "scribe_v2"
        private val WAV = "audio/wav".toMediaType()
    }
}
