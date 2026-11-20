package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.net.StalledConnectionServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ElevenLabsTranscriberTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun transcriber() = ElevenLabsTranscriber("xi-key", "scribe_v2", "en", server.url("/").toString(), http)

    @Test
    fun postsScribeRequestWithKeyHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"language_code":"en","text":" diesel nearby ","words":[]}"""))
        assertEquals("diesel nearby", transcriber().transcribe(ByteArray(3_200), 16_000))
        val req = server.takeRequest()
        assertEquals("/v1/speech-to-text", req.path)
        assertEquals("xi-key", req.getHeader("xi-api-key"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"model_id\""))
        assertTrue(body.contains("scribe_v2"))
        assertTrue(body.contains("name=\"language_code\""))
        assertTrue(body.contains("filename=\"speech.wav\""))
        assertTrue(body.contains("RIFF"))
    }

    @Test
    fun errorsAreShortMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}"""))
        try {
            transcriber().transcribe(ByteArray(10), 16_000)
            fail()
        } catch (e: AgentException) {
            assertTrue(e.message!!.startsWith("Transcription error 401"))
        }
    }

    /**
     * Regression (review finding on #54): Stop cancels the transcription coroutine, and that
     * must abort the stalled HTTP call instead of waiting out OkHttp's read timeout.
     */
    @Test
    fun stopCancelsAStalledTranscriptionAndClosesTheConnection() {
        val stalled = StalledConnectionServer().start()
        val slowHttp = OkHttpClient.Builder()
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        val transcriber = ElevenLabsTranscriber("xi-key", http = slowHttp, baseUrl = stalled.url)
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
