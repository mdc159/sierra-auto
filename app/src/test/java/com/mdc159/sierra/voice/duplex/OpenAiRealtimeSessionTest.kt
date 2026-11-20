package com.mdc159.sierra.voice.duplex

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

class OpenAiRealtimeSessionTest {

    private lateinit var server: FakeOpenAiServer
    private val bridge = RecordingBridge()
    private val tools = ToolRegistry(listOf(AskAgentTool(bridge)))
    private val sink = EventSink()
    private var session: OpenAiRealtimeSession? = null

    @Before
    fun setUp() {
        server = FakeOpenAiServer().apply { start() }
    }

    @After
    fun tearDown() {
        session?.close()
        server.shutdown()
    }

    private fun config() = DuplexConfig(
        provider = DuplexProvider.OPENAI_REALTIME,
        openAiBaseUrl = server.baseUrl() + "/v1",
        openAiApiKey = "sk-test",
        openAiModel = "gpt-realtime",
        openAiVoice = "marin",
        instructions = "Be brief.",
    )

    private fun connect(): OpenAiRealtimeSession {
        val s = OpenAiRealtimeSession(config(), tools, testClient, testTiming)
        session = s
        s.start(sink)
        return s
    }

    @Test
    fun handshakeSendsSessionUpdateWithAudioVadAndTools() {
        connect()
        val request = server.server.takeRequest()
        assertEquals("/v1/realtime?model=gpt-realtime", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        assertEquals("websocket", request.getHeader("Upgrade"))

        val update = server.awaitType("session.update")
        val s = update.getJSONObject("session")
        assertEquals("realtime", s.getString("type"))
        assertEquals("Be brief.", s.getString("instructions"))
        val input = s.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(24_000, input.getJSONObject("format").getInt("rate"))
        val vad = input.getJSONObject("turn_detection")
        assertEquals("server_vad", vad.getString("type"))
        assertTrue(vad.getBoolean("create_response"))
        assertTrue(vad.getBoolean("interrupt_response"))
        assertEquals("gpt-4o-mini-transcribe", input.getJSONObject("transcription").getString("model"))
        val output = s.getJSONObject("audio").getJSONObject("output")
        assertEquals("audio/pcm", output.getJSONObject("format").getString("type"))
        assertEquals("marin", output.getString("voice"))
        val tool = s.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("ask_agent", tool.getString("name"))
        assertEquals("string", tool.getJSONObject("parameters").getJSONObject("properties").getJSONObject("question").getString("type"))
        assertEquals("auto", s.getString("tool_choice"))

        sink.await<DuplexEvent.Connected>()
        assertEquals(24_000, session!!.inputSampleRate)
        assertEquals(24_000, session!!.outputSampleRate)
    }

    @Test
    fun micAudioIsBase64AppendedAndAssistantAudioIsDecoded() {
        val s = connect()
        val mic = ByteArray(960) { (it * 7).toByte() }
        s.sendAudio(mic)
        val append = server.awaitType("input_audio_buffer.append")
        assertArrayEquals(mic, unb64(append.getString("audio")))

        // Only part of a buffer.
        s.sendAudio(mic, 10, 20)
        assertArrayEquals(mic.copyOfRange(10, 30), unb64(server.awaitType("input_audio_buffer.append").getString("audio")))

        val speech = ByteArray(4800) { (it % 13).toByte() }
        server.responseCreated()
        server.audioDelta(speech)
        val audio = sink.await<DuplexEvent.AssistantAudio>()
        assertArrayEquals(speech, audio.pcm)
        assertEquals(24_000, audio.sampleRate)
        assertEquals("item_a", audio.itemId)

        // The beta-era event name is accepted too (a gateway pinned to the old protocol).
        server.audioDelta(speech, type = "response.audio.delta")
        assertEquals(2, sink.all<DuplexEvent.AssistantAudio>().let { sink.await<DuplexEvent.AssistantAudio>(); sink.all<DuplexEvent.AssistantAudio>().size })
    }

    @Test
    fun transcriptsForBothSidesArriveAsPartialThenFinal() {
        connect()
        server.send(JSONObject().put("type", "conversation.item.input_audio_transcription.delta").put("item_id", "u1").put("delta", "find "))
        server.send(JSONObject().put("type", "conversation.item.input_audio_transcription.delta").put("item_id", "u1").put("delta", "fuel"))
        server.send(JSONObject().put("type", "conversation.item.input_audio_transcription.completed").put("item_id", "u1").put("transcript", " find fuel "))
        assertEquals("find ", sink.await<DuplexEvent.UserTranscript>().text)
        assertEquals("find fuel", sink.await<DuplexEvent.UserTranscript> { !it.final }.text)
        assertEquals("find fuel", sink.await<DuplexEvent.UserTranscript> { it.final }.text)

        server.responseCreated()
        server.send(JSONObject().put("type", "response.output_audio_transcript.delta").put("delta", "Turn "))
        server.send(JSONObject().put("type", "response.output_audio_transcript.delta").put("delta", "left."))
        server.send(JSONObject().put("type", "response.output_audio_transcript.done").put("transcript", "Turn left."))
        assertEquals("Turn left.", sink.await<DuplexEvent.AssistantTranscript> { !it.final && it.text.endsWith(".") }.text)
        assertEquals("Turn left.", sink.await<DuplexEvent.AssistantTranscript> { it.final }.text)
        server.responseDone()
        assertFalse(sink.await<DuplexEvent.AssistantDone>().cancelled)
    }

    @Test
    fun speechStartedCutsPlaybackAndTruncateMatchesWhatWasPlayed() {
        val s = connect()
        server.responseCreated()
        server.audioDelta(ByteArray(48_000)) // 1000 ms at 24 kHz
        sink.await<DuplexEvent.AssistantAudio>()
        server.send(JSONObject().put("type", "input_audio_buffer.speech_started").put("audio_start_ms", 1200).put("item_id", "u2"))
        sink.await<DuplexEvent.UserStartedSpeaking>()

        // The controller reports how far playback got; the server already cancelled, so no response.cancel.
        s.interrupt(playedMs = 300)
        val truncate = server.awaitType("conversation.item.truncate")
        assertEquals("item_a", truncate.getString("item_id"))
        assertEquals(0, truncate.getInt("content_index"))
        assertEquals(300, truncate.getInt("audio_end_ms"))
        server.noMessage("response.cancel")

        server.responseDone(status = "cancelled")
        assertTrue(sink.await<DuplexEvent.AssistantDone>().cancelled)

        // A second interrupt for the same item is not repeated.
        s.interrupt(400)
        server.noMessage("conversation.item.truncate")
    }

    @Test
    fun tapInterruptCancelsResponseAndClampsTruncateToReceivedAudio() {
        val s = connect()
        server.responseCreated("resp_2")
        server.audioDelta(ByteArray(24_000), itemId = "item_b") // 500 ms
        sink.await<DuplexEvent.AssistantAudio>()
        s.interrupt(playedMs = 5_000)
        server.awaitType("response.cancel")
        val truncate = server.awaitType("conversation.item.truncate")
        assertEquals("item_b", truncate.getString("item_id"))
        assertEquals(500, truncate.getInt("audio_end_ms"))
    }

    @Test
    fun toolCallRoundTripReturnsFunctionOutputAndAsksForAResponse() {
        connect()
        server.send(
            JSONObject()
                .put("type", "response.function_call_arguments.done")
                .put("response_id", "resp_1")
                .put("item_id", "fc_1")
                .put("call_id", "call_42")
                .put("name", "ask_agent")
                .put("arguments", """{"question":"When is the oil change due?"}"""),
        )
        val call = sink.await<DuplexEvent.ToolCall>()
        assertEquals("call_42", call.callId)
        assertEquals("ask_agent", call.name)
        assertEquals("When is the oil change due?", bridge.questions.take())

        val output = server.awaitType("conversation.item.create")
        val item = output.getJSONObject("item")
        assertEquals("function_call_output", item.getString("type"))
        assertEquals("call_42", item.getString("call_id"))
        assertEquals("Oil change is due at 90,000 miles.", item.getString("output"))
        server.awaitType("response.create")
        val result = sink.await<DuplexEvent.ToolResult>()
        assertFalse(result.isError)
        assertEquals("Oil change is due at 90,000 miles.", result.result)
    }

    @Test
    fun unknownToolIsReportedAsAnErrorOutput() {
        connect()
        server.send(
            JSONObject()
                .put("type", "response.function_call_arguments.done")
                .put("call_id", "call_7")
                .put("name", "fly_away")
                .put("arguments", "{}"),
        )
        val item = server.awaitType("conversation.item.create").getJSONObject("item")
        assertTrue(item.getString("output").startsWith("Error: Unknown tool"))
        assertTrue(sink.await<DuplexEvent.ToolResult>().isError)
    }

    @Test
    fun errorEventsAreSurfacedWithoutEndingTheSession() {
        connect()
        server.send(JSONObject().put("type", "error").put("error", JSONObject().put("type", "invalid_request_error").put("message", "Cancellation failed")))
        val error = sink.await<DuplexEvent.Error>()
        assertEquals("Cancellation failed", error.message)
        assertFalse(error.fatal)
        assertTrue(sink.all<DuplexEvent.Ended>().isEmpty())
    }

    @Test
    fun handshakeTimesOutWhenTheServerStaysSilent() {
        server.sendSessionCreated = false
        server.confirmSessionUpdate = false
        val s = OpenAiRealtimeSession(config(), tools, testClient, testTiming)
        session = s
        try {
            s.start(sink)
            fail("expected a timeout")
        } catch (e: DuplexException) {
            assertEquals("The voice service did not answer in time.", e.message)
        }
    }

    @Test
    fun handshakeContinuesWhenOnlySessionCreatedArrives() {
        // A gateway that forwards audio but swallows session.updated.
        server.confirmSessionUpdate = false
        connect()
        val warning = sink.await<DuplexEvent.Error>()
        assertFalse(warning.fatal)
        sink.await<DuplexEvent.Connected>()
    }

    @Test
    fun closeDuringHandshakeUnblocksStartWithoutConnectedEvent() {
        server.sendSessionCreated = false
        server.confirmSessionUpdate = false
        val s = OpenAiRealtimeSession(config(), tools, testClient, testTiming.copy(handshakeTimeoutMs = 10_000))
        session = s
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val start = pool.submit { s.start(sink) }
            server.awaitType("session.update")
            s.close()
            try {
                start.get(1, java.util.concurrent.TimeUnit.SECONDS)
                fail("Stopped handshake must not report a successful connection")
            } catch (e: java.util.concurrent.ExecutionException) {
                assertTrue(e.cause is DuplexException)
            }
            assertTrue(sink.all<DuplexEvent.Connected>().isEmpty())
            assertEquals(1, sink.all<DuplexEvent.Ended>().size)
        } finally { s.close(); pool.shutdownNow() }
    }

    @Test
    fun handshakeErrorFromServerFailsStart() {
        server.confirmSessionUpdate = false
        server.sendSessionCreated = false
        val s = OpenAiRealtimeSession(config(), tools, testClient, testTiming)
        session = s
        Thread {
            // Wait for the client to be connected, then refuse it.
            server.awaitType("session.update")
            server.send(JSONObject().put("type", "error").put("error", JSONObject().put("message", "Invalid API key")))
        }.start()
        try {
            s.start(sink)
            fail("expected failure")
        } catch (e: DuplexException) {
            assertEquals("Invalid API key", e.message)
        }
    }

    @Test
    fun refusedUpgradeFailsStartWithHttpCode() {
        server.maxConnections = 0
        val s = OpenAiRealtimeSession(config(), tools, testClient, testTiming)
        session = s
        try {
            s.start(sink)
            fail("expected failure")
        } catch (e: DuplexException) {
            assertEquals("The voice service refused the connection (HTTP 503).", e.message)
        }
    }

    @Test
    fun droppedConnectionReconnectsAndRedoesTheHandshake() {
        server.dropFirstConnectionWithCode = 1011
        connect()
        val reconnecting = sink.await<DuplexEvent.Reconnecting>()
        assertEquals(1, reconnecting.attempt)
        assertTrue(reconnecting.reason, reconnecting.reason.startsWith("Connection closed (1011"))
        sink.await<DuplexEvent.Connected>(timeoutMs = 5_000) { sink.all<DuplexEvent.Connected>().size >= 2 }
        assertEquals(2, server.connections.get())
        // Both connections configured the session.
        server.awaitType("session.update")
        server.awaitType("session.update")
        assertNull(sink.all<DuplexEvent.Ended>().firstOrNull())
    }

    @Test
    fun givesUpAfterMaxReconnectsAndEnds() {
        server.dropFirstConnectionWithCode = 1011
        connect()
        sink.await<DuplexEvent.Reconnecting>()
        server.maxConnections = 1 // every retry gets HTTP 503
        val fatal = sink.await<DuplexEvent.Error>(timeoutMs = 5_000) { it.fatal }
        assertEquals("Lost the voice connection.", fatal.message)
        assertEquals("Connection lost", sink.await<DuplexEvent.Ended>().reason)
    }

    @Test
    fun closeSendsNormalClosureAndEmitsEndedOnce() {
        val s = connect()
        s.close()
        s.close()
        val (code, _) = server.closes.poll(3, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals(1000, code)
        sink.await<DuplexEvent.Ended>()
        Thread.sleep(100)
        assertEquals(1, sink.all<DuplexEvent.Ended>().size)
    }

    @Test
    fun serverClosingNormallyEndsTheSession() {
        connect()
        server.socket!!.close(1000, "Goodbye")
        assertEquals("Goodbye", sink.await<DuplexEvent.Ended>().reason)
    }

    @Test
    fun sendTextCreatesAUserMessageAndRequestsAResponse() {
        val s = connect()
        s.sendText("What's the weather?")
        val item = server.awaitType("conversation.item.create").getJSONObject("item")
        assertEquals("message", item.getString("type"))
        assertEquals("user", item.getString("role"))
        assertEquals("What's the weather?", item.getJSONArray("content").getJSONObject(0).getString("text"))
        server.awaitType("response.create")
    }
}
