package com.mdc159.sierra.agent

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Malformed and slow answers from the agent and transcription endpoints. */
class AgentClientEdgeCasesTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(1, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = AgentClient(
        AgentConfig(baseUrl = server.url("/v1").toString(), apiKey = "k", model = "m", sttUrl = server.url("/v1").toString()),
        http,
    )

    private fun chat(): String = client().chat(listOf(ChatMessage("user", "hi")))

    @Test
    fun malformedChatBodiesAreOneReadableError() {
        val bodies = listOf(
            "<html>502 Bad Gateway</html>",
            "",
            "null",
            "[]",
            """{"choices":[]}""",
            """{"choices":[{}]}""",
            """{"choices":[{"message":"not an object"}]}""",
            """{"choices":"nope"}""",
            """{"choices":[{"message":{"content":"   "}}]}""",
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setBody(body))
            val e = assertThrows("body '$body'", AgentException::class.java) { chat() }
            assertTrue(
                "body '$body' gave '${e.message}'",
                e.message == "The agent sent a reply Sierra couldn't read." || e.message == "The agent sent an empty reply.",
            )
        }
    }

    @Test
    fun extraFieldsAndWhitespaceAreTolerated() {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"x","object":"chat.completion","choices":[{"index":0,"finish_reason":"stop",
                   "message":{"role":"assistant","content":"\n  Turn left.  \n","refusal":null}}],"usage":{}}"""
            )
        )
        assertEquals("Turn left.", chat())
    }

    @Test
    fun httpErrorsWithUnreadableBodiesStayShort() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>" + "x".repeat(5_000)))
        val e = assertThrows(AgentException::class.java) { chat() }
        assertTrue(e.message!!.startsWith("Agent error 502: <html>"))
        assertTrue(e.message!!.length <= 120)

        server.enqueue(MockResponse().setResponseCode(429).setBody(""))
        assertEquals("Agent error 429", assertThrows(AgentException::class.java) { chat() }.message)

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"  boom \n  now "}}"""))
        assertEquals("Agent error 500: boom now", assertThrows(AgentException::class.java) { chat() }.message)
    }

    @Test
    fun slowBodyAfterHeadersIsATimeout() {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"late"}}]}""")
                .setBodyDelay(3, TimeUnit.SECONDS)
        )
        val e = assertThrows(AgentException::class.java) { chat() }
        assertEquals("Timed out. Check signal and try again.", e.message)
    }

    @Test
    fun disconnectDuringBodyIsReadable() {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"half"""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val e = assertThrows(AgentException::class.java) { chat() }
        assertTrue(e.message!!.isNotBlank())
        assertTrue(e.message!!.length <= 120)
    }

    @Test
    fun connectionRefusedIsReadable() {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/v1").toString()
        dead.shutdown()
        val e = assertThrows(AgentException::class.java) {
            AgentClient(AgentConfig(baseUrl = url, model = "m"), http).chat(listOf(ChatMessage("user", "hi")))
        }
        assertEquals("Couldn't reach the server.", e.message)
    }

    @Test
    fun malformedTranscriptionBodiesAreReadable() {
        for (body in listOf("<html>", "", "[]", "null")) {
            server.enqueue(MockResponse().setBody(body))
            val e = assertThrows("body '$body'", AgentException::class.java) { client().transcribe(byteArrayOf(1)) }
            assertEquals("Transcription reply couldn't be read.", e.message)
        }
    }

    @Test
    fun transcriptionWithoutTextIsEmptyNotAnError() {
        server.enqueue(MockResponse().setBody("""{"segments":[]}"""))
        assertEquals("", client().transcribe(byteArrayOf(1)))
    }

    @Test
    fun transcriptionTimeoutIsReadable() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = assertThrows(AgentException::class.java) { client().transcribe(byteArrayOf(1)) }
        assertEquals("Timed out. Check signal and try again.", e.message)
    }

    @Test
    fun transcriptionHttpErrorNamesTheService() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":{"message":"file too large"}}"""))
        val e = assertThrows(AgentException::class.java) { client().transcribe(ByteArray(10)) }
        assertEquals("Transcription error 413: file too large", e.message)
    }
}
