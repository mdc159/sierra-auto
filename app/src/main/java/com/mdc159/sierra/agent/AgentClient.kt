package com.mdc159.sierra.agent

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

data class ChatMessage(val role: String, val content: String)

/** Thrown with a short message that can go straight onto the car screen. */
class AgentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Minimal OpenAI-compatible client: chat completions and audio transcriptions. */
class AgentClient(
    private val config: AgentConfig,
    private val client: OkHttpClient = Http.client,
) {

    fun chat(messages: List<ChatMessage>): String {
        val body = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
                },
            )
        val request = Request.Builder()
            .url(config.chatCompletionsUrl())
            .withAuth(config.apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = execute(request, "Agent")
        try {
            val content = JSONObject(json)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
            if (content.isEmpty()) throw AgentException("The agent sent an empty reply.")
            return content
        } catch (e: JSONException) {
            throw AgentException("The agent sent a reply Sierra couldn't read.", e)
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

    private fun execute(request: Request, what: String): String {
        val response: Response = try {
            client.newCall(request).execute()
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
            if (!it.isSuccessful) throw AgentException("$what error ${it.code}: ${errorDetail(text)}".trimEnd(' ', ':'))
            return text
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WAV = "audio/wav".toMediaType()

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
    }
}
