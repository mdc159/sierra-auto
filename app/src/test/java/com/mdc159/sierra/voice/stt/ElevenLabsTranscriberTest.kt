package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.agent.AgentException
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
}
