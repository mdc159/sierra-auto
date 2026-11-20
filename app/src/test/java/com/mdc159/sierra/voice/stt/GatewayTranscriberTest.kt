package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.net.StalledConnectionServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * Regression (review finding on #54): Stop cancels the transcription coroutine, and that
     * must abort the stalled HTTP call instead of waiting out OkHttp's read timeout. The
     * stalled server never answers, so a prompt return and a closed connection are both proof
     * that cancellation reached the call.
     */
    @Test
    fun stopCancelsAStalledTranscriptionAndClosesTheConnection() {
        val stalled = StalledConnectionServer().start()
        val slowHttp = OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val transcriber = GatewayTranscriber(stalled.url, "key", http = slowHttp)
        try {
            val startedAt = System.nanoTime()
            runBlocking {
                val job = launch { transcriber.transcribe(ByteArray(3_200), 16_000) }
                delay(300) // let the request reach the stalled server
                job.cancel()
                job.join()
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("transcribe returned after ${elapsedMs}ms", elapsedMs < 2_500)
            assertTrue("the transcription connection stayed open", stalled.clientClosedWithin(2_000))
        } finally {
            stalled.shutdown()
        }
    }
}
