package com.mdc159.sierra.agent

import com.mdc159.sierra.net.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional end-to-end checks against a real OpenAI-compatible endpoint. Skipped unless
 * SIERRA_LIVE_BASE_URL and SIERRA_LIVE_MODEL are set (SIERRA_LIVE_API_KEY optional).
 * The transcription check also needs SIERRA_LIVE_STT_MODEL and SIERRA_LIVE_TTS_MODEL:
 * it synthesises a spoken question with /audio/speech, then transcribes it.
 * CI never sets these, and no key is stored in the repo.
 */
class LiveAgentTest {

    private val base = System.getenv("SIERRA_LIVE_BASE_URL").orEmpty()
    private val key = System.getenv("SIERRA_LIVE_API_KEY").orEmpty()
    private val model = System.getenv("SIERRA_LIVE_MODEL").orEmpty()

    @Test
    fun realEndpointAnswersBriefly() {
        assumeTrue("live endpoint not configured", base.isNotBlank() && model.isNotBlank())
        val client = AgentClient(AgentConfig(baseUrl = base, apiKey = key, model = model))
        val reply = client.chat(Conversation().messagesFor("How far is it from Sacramento to Reno, roughly?"))
        println("Live reply: $reply")
        assertTrue(reply.isNotBlank())
    }

    @Test
    fun realTranscriptionOfSynthesisedSpeech() {
        val stt = System.getenv("SIERRA_LIVE_STT_MODEL").orEmpty()
        val tts = System.getenv("SIERRA_LIVE_TTS_MODEL").orEmpty()
        assumeTrue("live transcription not configured", base.isNotBlank() && stt.isNotBlank() && tts.isNotBlank())

        val config = AgentConfig(baseUrl = base, apiKey = key, model = model, sttUrl = base, sttModel = stt)
        val speech = JSONObject()
            .put("model", tts)
            .put("voice", "alloy")
            .put("input", "Find the nearest gas station.")
            .put("response_format", "wav")
        val wav = Http.client.newCall(
            Request.Builder()
                .url(AgentConfig.endpoint(base, "audio/speech"))
                .header("Authorization", "Bearer $key")
                .post(speech.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { it.body!!.bytes() }

        val text = AgentClient(config).transcribe(wav)
        println("Live transcript: $text")
        assertTrue(text.lowercase().contains("gas"))
    }
}
