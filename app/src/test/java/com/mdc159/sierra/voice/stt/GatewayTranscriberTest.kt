package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.agent.AgentException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GatewayTranscriberTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun transcriber(model: String = "groq/whisper-large-v3-turbo") =
        GatewayTranscriber(server.url("/v1").toString(), "key", model, http = http)

    @Test
    fun postsWavMultipartWithChosenModel() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":" find fuel "}"""))
        val text = transcriber("openai/gpt-4o-transcribe").transcribe(ByteArray(3_200), 16_000)
        assertEquals("find fuel", text)
        val req = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req.path)
        assertEquals("Bearer key", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("openai/gpt-4o-transcribe"))
        assertTrue(body.contains("filename=\"speech.wav\""))
        assertTrue(body.contains("RIFF"))
    }

    @Test
    fun errorsBecomeShortMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))
        try {
            transcriber().transcribe(ByteArray(10), 16_000)
            fail()
        } catch (e: AgentException) {
            assertEquals("Transcription error 429: rate limited", e.message)
        }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try {
            transcriber().transcribe(ByteArray(10), 16_000)
            fail()
        } catch (e: AgentException) {
            assertEquals("Timed out. Check signal and try again.", e.message)
        }
        server.enqueue(MockResponse().setBody("not json"))
        try {
            transcriber().transcribe(ByteArray(10), 16_000)
            fail()
        } catch (e: AgentException) {
            assertEquals("Transcription reply couldn't be read.", e.message)
        }
    }
}
