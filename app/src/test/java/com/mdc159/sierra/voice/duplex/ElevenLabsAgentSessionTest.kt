package com.mdc159.sierra.voice.duplex

import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ElevenLabsAgentSessionTest {

    private lateinit var server: FakeElevenServer
    private val bridge = RecordingBridge("Tire pressure should be 35 psi.")
    private val tools = ToolRegistry(listOf(AskAgentTool(bridge)))
    private val sink = EventSink()
    private var session: ElevenLabsAgentSession? = null

    @Before
    fun setUp() {
        server = FakeElevenServer().apply { start() }
    }

    @After
    fun tearDown() {
        session?.close()
        server.shutdown()
    }

    private fun config(apiKey: String = "") = DuplexConfig(
        provider = DuplexProvider.ELEVENLABS,
        elevenBaseUrl = server.baseUrl(),
        elevenApiKey = apiKey,
        elevenAgentId = "agent_123",
        elevenVoiceId = "voice_9",
        instructions = "Be brief.",
    )

    private fun connect(apiKey: String = ""): ElevenLabsAgentSession {
        val s = ElevenLabsAgentSession(config(apiKey), tools, testClient, testTiming)
        session = s
        s.start(sink)
        return s
    }

    @Test
    fun publicAgentConnectsByAgentIdAndNegotiatesAudioFormats() {
        val s = connect()
        val request = server.server.takeRequest()
        assertEquals("/v1/convai/conversation?agent_id=agent_123", request.path)
        assertNull(request.getHeader("xi-api-key"))

        val init = server.awaitType("conversation_initiation_client_data")
        val override = init.getJSONObject("conversation_config_override")
        assertEquals("Be brief.", override.getJSONObject("agent").getJSONObject("prompt").getString("prompt"))
        assertEquals("voice_9", override.getJSONObject("tts").getString("voice_id"))
        assertEquals("sierra_auto", init.getJSONObject("source_info").getString("source"))

        sink.await<DuplexEvent.Connected>()
        assertEquals(24_000, s.outputSampleRate)
        assertEquals(16_000, s.inputSampleRate)
        assertEquals("conv_123", s.conversationId)
    }

    @Test
    fun privateAgentFetchesASignedUrlWithTheApiKey() {
        server.httpResponder = { request ->
            if (request.path!!.startsWith("/v1/convai/conversation/get-signed-url")) {
                MockResponse().setBody(
                    JSONObject().put("signed_url", server.baseUrl().replaceFirst("http", "ws") + "/v1/convai/conversation?agent_id=agent_123&token=tok_abc").toString(),
                )
            } else {
                null
            }
        }
        connect(apiKey = "xi-secret")
        val signed = server.server.takeRequest()
        assertEquals("GET", signed.method)
        assertEquals("/v1/convai/conversation/get-signed-url?agent_id=agent_123", signed.path)
        assertEquals("xi-secret", signed.getHeader("xi-api-key"))
        val upgrade = server.server.takeRequest()
        assertEquals("/v1/convai/conversation?agent_id=agent_123&token=tok_abc", upgrade.path)
        assertEquals("websocket", upgrade.getHeader("Upgrade"))
        sink.await<DuplexEvent.Connected>()
    }

    @Test
    fun signedUrlRefusalFailsStart() {
        server.httpResponder = { MockResponse().setResponseCode(401).setBody("""{"detail":"bad key"}""") }
        val s = ElevenLabsAgentSession(config("xi-bad"), tools, testClient, testTiming)
        session = s
        try {
            s.start(sink)
            fail("expected failure")
        } catch (e: DuplexException) {
            assertEquals("ElevenLabs refused the sign-in (HTTP 401).", e.message)
        }
    }

    @Test
    fun micAudioGoesAsUserAudioChunkWithoutATypeField() {
        val s = connect()
        val mic = ByteArray(640) { (it * 3).toByte() }
        s.sendAudio(mic)
        val chunk = server.await { it.has("user_audio_chunk") }
        assertFalse(chunk.has("type"))
        assertArrayEquals(mic, unb64(chunk.getString("user_audio_chunk")))
    }

    @Test
    fun agentAudioIsDecodedAtTheNegotiatedRate() {
        connect()
        val pcm = ByteArray(2400) { (it % 11).toByte() }
        server.audio(pcm, eventId = 1)
        val audio = sink.await<DuplexEvent.AssistantAudio>()
        assertArrayEquals(pcm, audio.pcm)
        assertEquals(24_000, audio.sampleRate)
    }

    @Test
    fun interruptionDropsAudioUpToTheNamedEventId() {
        connect()
        server.audio(ByteArray(100), eventId = 1)
        sink.await<DuplexEvent.AssistantAudio>()
        server.send(JSONObject().put("type", "interruption").put("interruption_event", JSONObject().put("event_id", 2)))
        sink.await<DuplexEvent.UserStartedSpeaking>()
        assertTrue(sink.await<DuplexEvent.AssistantDone>().cancelled)
        server.audio(ByteArray(100), eventId = 2) // stale, must be dropped
        server.audio(ByteArray(300), eventId = 3) // next turn, must arrive
        val next = sink.await<DuplexEvent.AssistantAudio> { it.pcm.size == 300 }
        assertEquals(300, next.pcm.size)
        assertEquals(2, sink.all<DuplexEvent.AssistantAudio>().size)
    }

    @Test
    fun tapInterruptMutesTheRestOfTheReplyUntilTheNextTurn() {
        val s = connect()
        server.audio(ByteArray(100), eventId = 1)
        sink.await<DuplexEvent.AssistantAudio>()
        s.interrupt(playedMs = 50)
        assertTrue(sink.await<DuplexEvent.AssistantDone>().cancelled)
        server.audio(ByteArray(100), eventId = 2)
        server.send(JSONObject().put("type", "user_transcript").put("user_transcription_event", JSONObject().put("user_transcript", "stop")))
        sink.await<DuplexEvent.UserTranscript>()
        server.send(JSONObject().put("type", "agent_response").put("agent_response_event", JSONObject().put("agent_response", "Okay.")))
        sink.await<DuplexEvent.AssistantTranscript> { it.final }
        server.audio(ByteArray(200), eventId = 3)
        sink.await<DuplexEvent.AssistantAudio> { it.pcm.size == 200 }
        assertEquals(2, sink.all<DuplexEvent.AssistantAudio>().size)
    }

    @Test
    fun transcriptsAndTentativeResponses() {
        connect()
        server.send(JSONObject().put("type", "user_transcript").put("user_transcription_event", JSONObject().put("user_transcript", " find fuel ")))
        val user = sink.await<DuplexEvent.UserTranscript>()
        assertEquals("find fuel", user.text)
        assertTrue(user.final)
        server.send(
            JSONObject().put("type", "internal_tentative_agent_response")
                .put("tentative_agent_response_internal_event", JSONObject().put("tentative_agent_response", "There is a")),
        )
        assertFalse(sink.await<DuplexEvent.AssistantTranscript>().final)
        server.send(JSONObject().put("type", "agent_response").put("agent_response_event", JSONObject().put("agent_response", "There is a station ahead.")))
        assertEquals("There is a station ahead.", sink.await<DuplexEvent.AssistantTranscript> { it.final }.text)
        server.send(
            JSONObject().put("type", "agent_response_correction").put(
                "agent_response_correction_event",
                JSONObject().put("original_agent_response", "There is a station ahead.").put("corrected_agent_response", "There is a"),
            ),
        )
        assertEquals("There is a", sink.await<DuplexEvent.AssistantTranscript> { it.final && it.text == "There is a" }.text)
    }

    @Test
    fun pingIsAnsweredWithPongCarryingTheEventId() {
        connect()
        server.send(JSONObject().put("type", "ping").put("ping_event", JSONObject().put("event_id", 77).put("ping_ms", 40)))
        val pong = server.awaitType("pong")
        assertEquals(77, pong.getInt("event_id"))
    }

    @Test
    fun clientToolCallRoundTrip() {
        connect()
        server.send(
            JSONObject().put("type", "client_tool_call").put(
                "client_tool_call",
                JSONObject()
                    .put("tool_name", "ask_agent")
                    .put("tool_call_id", "tc_1")
                    .put("parameters", JSONObject().put("question", "What tire pressure?"))
                    .put("event_id", 5),
            ),
        )
        val call = sink.await<DuplexEvent.ToolCall>()
        assertEquals("ask_agent", call.name)
        assertEquals("What tire pressure?", bridge.questions.take())
        val result = server.awaitType("client_tool_result")
        assertEquals("tc_1", result.getString("tool_call_id"))
        assertEquals("Tire pressure should be 35 psi.", result.getString("result"))
        assertFalse(result.getBoolean("is_error"))

        server.send(
            JSONObject().put("type", "client_tool_call").put(
                "client_tool_call",
                JSONObject().put("tool_name", "nope").put("tool_call_id", "tc_2").put("parameters", JSONObject()),
            ),
        )
        val bad = server.awaitType("client_tool_result")
        assertEquals("tc_2", bad.getString("tool_call_id"))
        assertTrue(bad.getBoolean("is_error"))
    }

    @Test
    fun unplayableOutputFormatFailsStart() {
        server.shutdown()
        server = FakeElevenServer(outputFormat = "ulaw_8000").apply { start() }
        val s = ElevenLabsAgentSession(config(), tools, testClient, testTiming)
        session = s
        try {
            s.start(sink)
            fail("expected failure")
        } catch (e: DuplexException) {
            assertTrue(e.message!!.contains("audio format"))
        }
    }

    @Test
    fun handshakeTimesOutWithoutMetadata() {
        server.sendMetadata = false
        val s = ElevenLabsAgentSession(config(), tools, testClient, testTiming)
        session = s
        try {
            s.start(sink)
            fail("expected timeout")
        } catch (e: DuplexException) {
            assertEquals("The voice service did not answer in time.", e.message)
        }
    }

    @Test
    fun reconnectsAfterAnAbnormalClose() {
        connect()
        server.socket!!.close(1011, "restart")
        assertEquals(1, sink.await<DuplexEvent.Reconnecting>().attempt)
        sink.await<DuplexEvent.Connected>(timeoutMs = 5_000) { sink.all<DuplexEvent.Connected>().size >= 2 }
        assertEquals(2, server.connections.get())
    }

    @Test
    fun agentEndingTheConversationEndsTheSession() {
        connect()
        server.socket!!.close(1000, "")
        assertEquals("The voice service ended the conversation.", sink.await<DuplexEvent.Ended>().reason)
    }

    @Test
    fun closeSendsNormalClosure() {
        val s = connect()
        s.close()
        val (code, reason) = server.closes.poll(3, TimeUnit.SECONDS)!!
        assertEquals(1000, code)
        assertEquals("User ended conversation", reason)
    }

    @Test
    fun sendTextUsesUserMessage() {
        val s = connect()
        s.sendText("hello")
        assertEquals("hello", server.awaitType("user_message").getString("text"))
    }

    @Test
    fun formatParsing() {
        assertEquals(16_000, ElevenLabsAgentSession.parseFormat("pcm_16000"))
        assertEquals(44_100, ElevenLabsAgentSession.parseFormat("pcm_44100"))
        assertNull(ElevenLabsAgentSession.parseFormat("ulaw_8000"))
        assertNull(ElevenLabsAgentSession.parseFormat("mp3_44100_128"))
    }
}
