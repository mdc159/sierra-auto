package com.mdc159.sierra.agent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AgentClientTest {

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

    private fun config(sttKey: String = "") = AgentConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "test-model",
        sttUrl = server.url("/").toString(),
        sttKey = sttKey,
    )

    @Test
    fun chatPostsOpenAiShapedRequestAndReadsReply() {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":" Turn left. "}}]}""")
        )
        val conversation = Conversation()
        val reply = AgentClient(config(), http).chat(conversation.messagesFor("Which way?"))
        assertEquals("Turn left.", reply)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Which way?", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test
    fun errorBodyBecomesReadableMessage() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""")
        )
        try {
            AgentClient(config(), http).chat(listOf(ChatMessage("user", "hi")))
            fail("expected AgentException")
        } catch (e: AgentException) {
            assertEquals("Agent error 401: Incorrect API key provided", e.message)
        }
    }

    @Test
    fun timeoutBecomesReadableMessage() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try {
            AgentClient(config(), http).chat(listOf(ChatMessage("user", "hi")))
            fail("expected AgentException")
        } catch (e: AgentException) {
            assertEquals("Timed out. Check signal and try again.", e.message)
        }
    }

    @Test
    fun malformedReplyIsReported() {
        server.enqueue(MockResponse().setBody("""{"nope":true}"""))
        try {
            AgentClient(config(), http).chat(listOf(ChatMessage("user", "hi")))
            fail("expected AgentException")
        } catch (e: AgentException) {
            assertEquals("The agent sent a reply Sierra couldn't read.", e.message)
        }
    }

    @Test
    fun transcribeSendsMultipartWavAndFallsBackToAgentKey() {
        server.enqueue(MockResponse().setBody("""{"text":" find gas "}"""))
        val text = AgentClient(config(), http).transcribe(byteArrayOf(1, 2, 3, 4))
        assertEquals("find gas", text)

        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("whisper-1"))
        assertTrue(body.contains("filename=\"speech.wav\""))
    }

    @Test
    fun transcribeUsesSeparateKeyWhenSet() {
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        AgentClient(config(sttKey = "stt-key"), http).transcribe(byteArrayOf(0))
        assertEquals("Bearer stt-key", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun endpointResolution() {
        assertEquals("https://h/v1/chat/completions", AgentConfig.endpoint("https://h", "chat/completions"))
        assertEquals("https://h/v1/chat/completions", AgentConfig.endpoint("https://h/v1/", "chat/completions"))
        assertEquals("https://h/api/v1/chat/completions", AgentConfig.endpoint("https://h/api/v1", "chat/completions"))
        assertEquals(
            "https://h/v1/chat/completions",
            AgentConfig.endpoint("https://h/v1/chat/completions", "chat/completions"),
        )
        assertEquals("https://h/v1beta/audio/transcriptions", AgentConfig.endpoint("https://h/v1beta", "audio/transcriptions"))
    }

    @Test
    fun errorDetailHandlesShapes() {
        assertEquals("bad", AgentClient.errorDetail("""{"error":"bad"}"""))
        assertEquals("nope", AgentClient.errorDetail("""{"detail":"nope"}"""))
        assertEquals("<html> gateway", AgentClient.errorDetail("<html>\n gateway"))
    }
}
