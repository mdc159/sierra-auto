package com.mdc159.sierra.voice.duplex

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the duplex fixes ported in 7b991f2..fb01020 (2026-11-20), which
 * arrived without tests. Each test pins one fix; the class docs of docs/DUPLEX.md
 * sections "Duplicate replies" and "Late and repeated events" describe the observed
 * incidents behind them.
 */
class OpenAiRealtimeSessionBehaviorTest {

    private lateinit var server: FakeOpenAiServer
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

    private fun connect(
        tools: ToolRegistry = ToolRegistry(emptyList()),
        credentials: (() -> DuplexConfig)? = null,
        listener: DuplexListener = sink,
    ): OpenAiRealtimeSession {
        val s = OpenAiRealtimeSession(config(), tools, testClient, testTiming, credentials)
        session = s
        s.start(listener)
        return s
    }

    // ---- server script helpers -------------------------------------------------------

    /** The `response.metadata` key the app tags its own response.create with. */
    private val metadataKey = "sierra_request"

    private fun created(id: String, tag: String? = null) = server.send(
        JSONObject().put("type", "response.created").put(
            "response",
            JSONObject().put("id", id).put("status", "in_progress").apply {
                if (tag != null) put("metadata", JSONObject().put(metadataKey, tag))
            },
        ),
    )

    /** [ms] of silence at 24 kHz, as one delta of [responseId]/[itemId]. */
    private fun delta(responseId: String, itemId: String, ms: Int = 200) = server.send(
        JSONObject()
            .put("type", "response.output_audio.delta")
            .put("response_id", responseId)
            .put("item_id", itemId)
            .put("output_index", 0)
            .put("content_index", 0)
            .put("delta", b64(ByteArray(ms * 48))),
    )

    private fun done(id: String, status: String = "completed") = server.send(
        JSONObject().put("type", "response.done").put("response", JSONObject().put("id", id).put("status", status)),
    )

    private fun committed() = server.send(JSONObject().put("type", "input_audio_buffer.committed"))

    private fun transcriptCompleted(text: String) = server.send(
        JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
            .put("item_id", "u1").put("transcript", text),
    )

    private fun speechStarted() = server.send(
        JSONObject().put("type", "input_audio_buffer.speech_started").put("audio_start_ms", 100).put("item_id", "u2"),
    )

    private fun speechStopped() = server.send(
        JSONObject().put("type", "input_audio_buffer.speech_stopped").put("audio_end_ms", 400),
    )

    private fun refusedCreate(eventId: String) = server.send(
        JSONObject().put("type", "error").put(
            "error",
            JSONObject()
                .put("type", "invalid_request_error")
                .put("code", "conversation_already_has_active_response")
                .put("event_id", eventId)
                .put("message", "A response is already in progress."),
        ),
    )

    private fun functionCall(callId: String) = server.send(
        JSONObject()
            .put("type", "response.function_call_arguments.done")
            .put("response_id", "resp_1")
            .put("item_id", "fc_1")
            .put("call_id", callId)
            .put("name", "ask_agent")
            .put("arguments", """{"question":"When is the oil change due?"}"""),
    )

    /** An ask_agent bridge the test holds back until it says so; cancel() lets it go. */
    private class BlockingBridge : AgentBridge {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        private val release = CountDownLatch(1)

        @Volatile
        var cancelCount = 0
            private set

        override fun ask(question: String): String {
            started.countDown()
            release.await()
            check(cancelCount == 0) { "Conversation stopped." }
            return "Oil change is due at 90,000 miles."
        }

        override fun cancel() {
            cancelCount++
            cancelled.countDown()
            release.countDown()
        }

        fun finish() = release.countDown()
    }

    // ---- cancelled replies ------------------------------------------------------------

    /**
     * 7b991f2: after a barge-in the server has cancelled its response, but deltas already
     * on the wire keep arriving. They must not be played (playback was already cut).
     */
    @Test
    fun audioOfAServerCancelledReplyIsNotPlayed() {
        connect()
        created("resp_1")
        speechStarted()
        sink.await<DuplexEvent.UserStartedSpeaking>()
        delta("resp_1", "item_a")
        delta("resp_1", "item_a")
        Thread.sleep(200)
        assertTrue("deltas of the cancelled reply were played", sink.all<DuplexEvent.AssistantAudio>().isEmpty())
        done("resp_1", status = "cancelled")
        assertTrue(sink.await<DuplexEvent.AssistantDone>().cancelled)
    }

    /**
     * 7b991f2: a tap interrupt cancels the active response and truncates its item; late
     * deltas of that response/item must be dropped. fb01020: response.cancel names the
     * response it cancels, so a late cancel cannot stop a newer one.
     */
    @Test
    fun audioOfATapCancelledReplyIsNotPlayedAndTheCancelNamesIt() {
        val s = connect()
        created("resp_1")
        delta("resp_1", "item_a")
        sink.await<DuplexEvent.AssistantAudio>()
        s.interrupt(playedMs = 100)
        assertEquals("resp_1", server.awaitType("response.cancel").getString("response_id"))
        server.awaitType("conversation.item.truncate")
        delta("resp_1", "item_a") // still in flight when the tap landed
        Thread.sleep(200)
        assertEquals(1, sink.all<DuplexEvent.AssistantAudio>().size)
    }

    /**
     * The audible item vs the latest one: items A and B of one reply are both in flight
     * (a tool preface, then its answer); the driver interrupts 150 ms into A. The service
     * must forget the rest of A after those 150 ms and all of B: truncating the latest
     * item instead left A fully heard in the model's context and cut B at an offset the
     * driver never heard.
     */
    @Test
    fun interruptDuringTwoItemsTruncatesTheAudibleItemAtItsOffsetAndBToNothing() {
        val s = connect()
        created("resp_1")
        delta("resp_1", "item_a", ms = 400)
        delta("resp_1", "item_b", ms = 400)
        sink.await<DuplexEvent.AssistantAudio> { it.itemId == "item_b" }
        s.interrupt(PlaybackCut(itemId = "item_a", playedMs = 150, unheardItemIds = listOf("item_b"))) // the playhead sits 150 ms into item_a
        val a = server.awaitType("conversation.item.truncate")
        assertEquals("item_a", a.getString("item_id"))
        assertEquals(150, a.getInt("audio_end_ms"))
        val b = server.awaitType("conversation.item.truncate")
        assertEquals("item_b", b.getString("item_id"))
        assertEquals(0, b.getInt("audio_end_ms"))
    }

    // ---- late and repeated response.done ----------------------------------------------

    /**
     * fb01020: after an upstream stall the server has sent a second response.done for a
     * response it had already completed. It must be ignored: it must neither emit a
     * cancelled AssistantDone (which cuts the reply now playing) nor end the active one.
     */
    @Test
    fun staleResponseDoneForAnEarlierReplyDoesNotCutTheOnePlaying() {
        val s = connect()
        created("resp_1")
        delta("resp_1", "item_a")
        done("resp_1")
        assertFalse(sink.await<DuplexEvent.AssistantDone>().cancelled)

        created("resp_2")
        delta("resp_2", "item_b")
        sink.await<DuplexEvent.AssistantAudio> { it.itemId == "item_b" }
        done("resp_1", status = "cancelled") // the stale repeat
        Thread.sleep(200)
        assertEquals("the stale done ended the current reply", 1, sink.all<DuplexEvent.AssistantDone>().size)

        // The active response is still resp_2: an interrupt still cancels it by id.
        s.interrupt(playedMs = 0)
        assertEquals("resp_2", server.awaitType("response.cancel").getString("response_id"))

        done("resp_2")
        val second = sink.await<DuplexEvent.AssistantDone> { sink.all<DuplexEvent.AssistantDone>().size >= 2 }
        assertFalse(second.cancelled)
    }

    /** fb01020: an exact repeat of a handled response.done is ignored. */
    @Test
    fun repeatedResponseDoneIsIgnored() {
        connect()
        created("resp_1")
        delta("resp_1", "item_a")
        done("resp_1")
        assertFalse(sink.await<DuplexEvent.AssistantDone>().cancelled)
        done("resp_1", status = "cancelled")
        Thread.sleep(200)
        assertEquals("the repeated done was handled again", 1, sink.all<DuplexEvent.AssistantDone>().size)
    }

    // ---- gateway duplicates -------------------------------------------------------------

    /**
     * b8bd973 + fb01020: the gateway (LiteLLM) sends its own response.create after an
     * input transcript; on a slow link the previous reply has finished generating, the
     * server accepts it, and the same turn is answered twice. The duplicate is cancelled
     * by id, its audio is dropped, and its response.done emits nothing (a cancelled
     * AssistantDone would cut the reply still playing).
     */
    @Test
    fun gatewayDuplicateReplyIsCancelledAndItsAudioDropped() {
        connect()
        committed()
        transcriptCompleted("find fuel")
        created("resp_1") // the server's own answer to the turn
        delta("resp_1", "item_a")
        done("resp_1")
        assertFalse(sink.await<DuplexEvent.AssistantDone>().cancelled)

        created("resp_dup") // the gateway's own response.create, accepted: same turn again
        assertEquals("resp_dup", server.awaitType("response.cancel").getString("response_id"))
        delta("resp_dup", "item_dup")
        Thread.sleep(200)
        assertTrue(
            "audio of the duplicate reply was played",
            sink.all<DuplexEvent.AssistantAudio>().none { it.itemId == "item_dup" },
        )
        done("resp_dup", status = "cancelled")
        Thread.sleep(200)
        assertEquals("the duplicate's done emitted an AssistantDone", 1, sink.all<DuplexEvent.AssistantDone>().size)
    }

    /**
     * fb01020: the duplicate canceller only acts within a short window after an input
     * transcript. A response.created long after any transcript (for example one the
     * server starts on its own) is not a duplicate and must play.
     */
    @Test
    fun unrequestedResponseLongAfterATranscriptIsNotCancelled() {
        connect()
        committed()
        transcriptCompleted("find fuel")
        created("resp_1")
        done("resp_1")
        sink.await<DuplexEvent.AssistantDone>()
        Thread.sleep(2_600) // past the 2.5 s duplicate window
        created("resp_late")
        server.noMessage("response.cancel", 250)
        delta("resp_late", "item_late")
        sink.await<DuplexEvent.AssistantAudio> { it.itemId == "item_late" }
        done("resp_late")
        sink.await<DuplexEvent.AssistantDone>()
    }

    /**
     * fb01020: the app's own response.create carries response.metadata, which the server
     * echoes, so the app's responses are recognised exactly. Here the app's request is
     * delayed upstream: another response is created and finishes first (consuming the
     * "something new" marker), and only then does the answer to the app's request arrive.
     * Without the metadata tag the app would cancel its own answer (observed 2026-11-20:
     * 35 deltas of the real answer dropped).
     */
    @Test
    fun theAppsOwnTaggedResponseIsNeverCancelledAsADuplicate() {
        val s = connect()
        committed()
        transcriptCompleted("find fuel")
        created("resp_1") // the server's answer to the driver's turn
        done("resp_1")
        sink.await<DuplexEvent.AssistantDone>()

        s.sendText("What now?") // the app's own response.create
        server.awaitType("conversation.item.create")
        val create = server.awaitType("response.create")
        val eventId = create.getString("event_id")
        assertTrue(eventId.startsWith("sierra_"))
        assertEquals(eventId, create.getJSONObject("response").getJSONObject("metadata").getString(metadataKey))

        // The gateway's racing response.create is accepted and answered first.
        created("resp_vad")
        done("resp_vad")
        sink.await<DuplexEvent.AssistantDone> { sink.all<DuplexEvent.AssistantDone>().size >= 2 }

        // Then the answer to the app's own request arrives, echoing the metadata tag.
        val echoedTag = create.optJSONObject("response")?.optJSONObject("metadata")?.optString(metadataKey)?.ifBlank { null }
        created("resp_own", tag = echoedTag)
        server.noMessage("response.cancel", 250)
        delta("resp_own", "item_own")
        sink.await<DuplexEvent.AssistantAudio> { it.itemId == "item_own" }
        done("resp_own")
        assertFalse(sink.await<DuplexEvent.AssistantDone> { sink.all<DuplexEvent.AssistantDone>().size >= 3 }.cancelled)
    }

    /**
     * fb01020: a response the server cancelled before it produced audio or a tool call
     * answered nothing, so the input it was for is still unanswered: the next response
     * to it is not a duplicate.
     */
    @Test
    fun aCancelledEmptyResponseDoesNotConsumeTheTurn() {
        connect()
        committed()
        transcriptCompleted("hello")
        created("resp_1") // the server's VAD response, producing nothing
        speechStarted() // the driver barges in; the server cancels the empty response
        done("resp_1", status = "cancelled")
        created("resp_2") // the retry for the same turn
        server.noMessage("response.cancel", 250)
        delta("resp_2", "item_b")
        sink.await<DuplexEvent.AssistantAudio> { it.itemId == "item_b" }
    }

    // ---- refused response.create --------------------------------------------------------

    /**
     * 9ec487a: the gateway sends its own response.create after every transcription, the
     * server refuses it, and the error arrives on our socket. It was not this app's
     * request: no error is surfaced and the session carries on.
     */
    @Test
    fun refusedForeignResponseCreateIsIgnored() {
        connect()
        refusedCreate(eventId = "litellm-transcript-gate-1")
        Thread.sleep(200)
        assertTrue("a foreign refusal was surfaced", sink.all<DuplexEvent.Error>().isEmpty())
        assertNull(sink.all<DuplexEvent.Ended>().firstOrNull())

        created("resp_1")
        delta("resp_1", "item_a")
        sink.await<DuplexEvent.AssistantAudio>()
    }

    /**
     * fb01020: when our own response.create is refused because a response is already
     * running, the request is deferred (not dropped, not an error) and sent again once
     * that response is done.
     */
    @Test
    fun ourRefusedResponseCreateIsDeferredAndRetried() {
        val s = connect()
        s.sendText("What now?")
        server.awaitType("conversation.item.create")
        val first = server.awaitType("response.create")
        refusedCreate(eventId = first.getString("event_id"))
        Thread.sleep(150)
        assertTrue("our refusal was surfaced as an error", sink.all<DuplexEvent.Error>().isEmpty())

        created("resp_a") // the response that was already running
        done("resp_a")
        sink.await<DuplexEvent.AssistantDone>()

        val retry = server.awaitType("response.create")
        assertTrue(retry.getString("event_id").startsWith("sierra_"))
        assertNotEquals(first.getString("event_id"), retry.getString("event_id"))
        assertEquals(
            retry.getString("event_id"),
            retry.getJSONObject("response").getJSONObject("metadata").getString(metadataKey),
        )
    }

    // ---- tool results -------------------------------------------------------------------

    /**
     * 7b991f2: a tool result that returns while another response is active sends its
     * function_call_output immediately but defers the response.create until that
     * response is done (the server refuses a second response.create).
     */
    @Test
    fun toolResultReturningMidReplyIsDeferredUntilTheReplyEnds() {
        val bridge = BlockingBridge()
        connect(ToolRegistry(listOf(AskAgentTool(bridge))))

        created("resp_1") // the tool call's preface
        functionCall("call_9")
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        done("resp_1")

        // The driver asks something else while the tool is still running.
        committed()
        created("resp_2")
        delta("resp_2", "item_b")
        sink.await<DuplexEvent.AssistantAudio> { it.itemId == "item_b" }

        bridge.finish() // the tool result returns mid-reply
        val output = server.await { it.optString("type") == "conversation.item.create" }
        assertEquals("function_call_output", output.getJSONObject("item").getString("type"))
        assertEquals("call_9", output.getJSONObject("item").getString("call_id"))
        server.noMessage("response.create", 300) // deferred, not sent into the active response

        done("resp_2")
        server.awaitType("response.create") // now it is asked for
    }

    /**
     * fb01020: a tool result that returns while the driver is speaking is deferred until
     * after the reply to that turn (it used to be skipped, and the answer was never spoken).
     */
    @Test
    fun toolResultReturningWhileTheDriverSpeaksIsAskedAfterThatReply() {
        val bridge = BlockingBridge()
        connect(ToolRegistry(listOf(AskAgentTool(bridge))))

        created("resp_1")
        functionCall("call_9")
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        done("resp_1")

        speechStarted() // the driver talks over the tool call
        sink.await<DuplexEvent.UserStartedSpeaking>()
        bridge.finish()
        server.await { it.optString("type") == "conversation.item.create" }
        server.noMessage("response.create", 300) // deferred while the driver is mid-turn

        speechStopped()
        created("resp_3") // the reply to the driver's words
        delta("resp_3", "item_c")
        done("resp_3")
        sink.await<DuplexEvent.AssistantDone>()
        server.awaitType("response.create") // the tool answer gets its own response after it
    }

    /**
     * response.done snapshots responsePending under responseLock, then unlocks before it
     * asks for the deferred response. A probe tick that drains the pending request inside
     * that gap (it sends the tagged response.create itself) must consume it: done asking
     * again re-defers behind the probe's own request, and when that answer finishes, a
     * third response.create goes out. The tool's one pending result would be answered
     * twice, the second reply tagged as the app's own request (past the duplicate
     * canceller). The claim of a pending request must be atomic with the send.
     */
    @Test
    fun aDeferredRequestDrainedByTheProbeInDonesGapIsNotAskedForAgain() {
        val bridge = BlockingBridge()
        val held = java.util.concurrent.atomic.AtomicReference<OpenAiRealtimeSession?>()
        val racing = object : DuplexListener {
            override fun onEvent(event: DuplexEvent) {
                // The done of resp_2 is emitting: this callback runs inside the gap
                // response.done leaves between releasing responseLock and asking for the
                // deferred response. Run the probe timer's work exactly there.
                if (event is DuplexEvent.AssistantDone && sink.all<DuplexEvent.AssistantDone>().size == 1) {
                    held.get()?.probeTick()
                }
                sink.onEvent(event)
            }
        }
        held.set(connect(ToolRegistry(listOf(AskAgentTool(bridge))), listener = racing))

        created("resp_1") // the tool call's preface
        functionCall("call_9")
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        done("resp_1")
        sink.await<DuplexEvent.AssistantDone>()

        // The driver asks something else while the tool runs; its result returns mid-reply
        // and is deferred (the server refuses a second response.create).
        committed()
        created("resp_2")
        delta("resp_2", "item_b")
        sink.await<DuplexEvent.AssistantAudio> { it.itemId == "item_b" }
        bridge.finish()
        server.await { it.optString("type") == "conversation.item.create" && it.optJSONObject("item")?.optString("type") == "function_call_output" }
        server.noMessage("response.create", 250) // deferred behind the active resp_2

        done("resp_2") // -> the racing listener runs probeTick() inside done's gap
        sink.await<DuplexEvent.AssistantDone> { sink.all<DuplexEvent.AssistantDone>().size >= 2 }
        val toolAnswer = server.awaitType("response.create") // claimed by the probe, once
        val tag = toolAnswer.getJSONObject("response").getJSONObject("metadata").getString(metadataKey)

        created("resp_tool", tag = tag)
        done("resp_tool")
        sink.await<DuplexEvent.AssistantDone> { sink.all<DuplexEvent.AssistantDone>().size >= 3 }
        // Only one pending result existed: no second response.create may follow the answer.
        server.noMessage("response.create", 400)
    }

    // ---- stop / tools / credentials ------------------------------------------------------

    /**
     * 7b991f2 (DuplexTool/Duplex): Stop must not wait for the owner's agent. Closing the
     * session cancels a tool call that is still running, through ToolRegistry.cancel →
     * AskAgentTool.cancel → AgentBridge.cancel.
     */
    @Test
    fun stopCancelsARunningAskAgentCall() {
        val bridge = BlockingBridge()
        val s = connect(ToolRegistry(listOf(AskAgentTool(bridge))))
        created("resp_1")
        functionCall("call_9")
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))

        s.close()
        assertTrue("the running ask_agent was not cancelled", bridge.cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, bridge.cancelCount)
    }

    /**
     * 7b991f2: the base URL and key are read on every connect (the credentials callback),
     * so a key the vault refreshed after a 401 reaches the reconnect instead of the one
     * captured when the session was built.
     */
    @Test
    fun reconnectUsesTheCurrentKeyNotTheOneFromSessionCreation() {
        val key = java.util.concurrent.atomic.AtomicReference("sk-old")
        connect(credentials = { config().copy(openAiApiKey = key.get()) })
        assertEquals("Bearer sk-old", server.server.takeRequest().getHeader("Authorization"))

        key.set("sk-new") // the vault refreshes the key while the session is live
        server.socket!!.close(1011, "going away")
        sink.await<DuplexEvent.Reconnecting>()
        sink.await<DuplexEvent.Connected>(timeoutMs = 5_000) { sink.all<DuplexEvent.Connected>().size >= 2 }
        assertEquals("Bearer sk-new", server.server.takeRequest().getHeader("Authorization"))
    }
}
