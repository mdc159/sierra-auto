package com.mdc159.sierra.voice.convo

import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.voice.FakeAgent
import com.mdc159.sierra.voice.FakeAudioFocus
import com.mdc159.sierra.voice.FakeMic
import com.mdc159.sierra.voice.FakeSpeechOutput
import com.mdc159.sierra.voice.FakeTranscriber
import com.mdc159.sierra.voice.stt.EndpointConfig
import com.mdc159.sierra.voice.stt.MicLevels
import com.mdc159.sierra.voice.tts.AudioFocusGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

class ConversationControllerTest {

    @Test fun providerErrorBodyIsNeverPassedToTheMicLogger() = runBlocking {
        utterance()
        agent.failWith = AgentException("Agent error 401: private-request-text sk-private-token")
        val logs = mutableListOf<String>()
        val c = ConversationController(mic, FakeTranscriber("test"), agent, speech, focus,
            ConversationConfig(endpoint = endpoint, continuous = false, speakErrors = false), log = { logs += it })
        withTimeout(5_000) { c.run() }
        assertTrue(logs.any { it == "ended reason=ERROR" })
        assertFalse(logs.joinToString().contains("private-request"))
        assertFalse(logs.joinToString().contains("sk-private"))
    }

    private val mic = FakeMic()
    private val focus = FakeAudioFocus()
    private val speech = FakeSpeechOutput()
    private val agent = FakeAgent()
    private val states = Collections.synchronizedList(mutableListOf<ConversationState>())
    private val exchanges = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    private val endpoint = EndpointConfig(silenceMs = 300, noSpeechMs = 1_500, maxMs = 10_000, minSpeechMs = 100)

    private fun controller(
        transcriber: FakeTranscriber,
        config: ConversationConfig = ConversationConfig(endpoint = endpoint, silenceTimeoutMs = 1_000),
    ) = ConversationController(
        mic, transcriber, agent, speech, focus, config,
        onState = { states += it },
        onExchange = { h, r -> exchanges += h to r },
    )

    /** One spoken utterance: a little noise, speech, then enough silence to end it. */
    private fun utterance() {
        mic.feedMs(100, 100)
        mic.feedMs(400, 3_000)
        mic.feedMs(400, 100)
    }

    private fun silence(ms: Int) = mic.feedMs(ms, 100)

    private fun kinds() = states.map { it::class.simpleName }

    @Test
    fun listensThinksSpeaksAndListensAgainWithoutATap() = runBlocking {
        utterance()
        utterance()
        silence(1_200) // nobody follows up: the conversation ends on its own
        val c = controller(FakeTranscriber("find fuel", "how far"))
        withTimeout(10_000) { c.run() }

        assertEquals(listOf("find fuel", "how far"), agent.asked)
        assertEquals(listOf("You said find fuel.", "You said how far."), speech.spoken)
        assertEquals(listOf("find fuel" to "You said find fuel.", "how far" to "You said how far."), exchanges)
        assertEquals(
            listOf("Listening", "Thinking", "Thinking", "Speaking", "Listening", "Thinking", "Thinking", "Speaking", "Listening", "Ended"),
            kinds(),
        )
        val ended = c.state.value as ConversationState.Ended
        assertEquals(EndReason.SILENCE, ended.reason)
        assertEquals("how far", ended.lastHeard)
        assertEquals("You said how far.", ended.lastReply)
        // Each listening turn took exclusive focus and released it; speaking is the fake's business.
        assertEquals(3, focus.requests.count { it == AudioFocusGate.Mode.LISTEN })
        assertEquals(3, focus.abandons)
        assertEquals(3, mic.started)
        assertEquals(3, mic.stopped)
        val second = states.filterIsInstance<ConversationState.Listening>()[1]
        assertEquals(2, second.turn)
        assertEquals("You said find fuel.", second.lastReply)
    }

    @Test
    fun stopPhraseEndsTheConversationAfterAGoodbye() = runBlocking {
        utterance()
        utterance()
        val c = controller(FakeTranscriber("what's the weather", "Okay, that's all."))
        withTimeout(10_000) { c.run() }
        assertEquals(listOf("what's the weather"), agent.asked)
        assertEquals(listOf("You said what's the weather.", "Okay."), speech.spoken)
        assertEquals(EndReason.STOP_PHRASE, (c.state.value as ConversationState.Ended).reason)
    }

    @Test
    fun firstTurnTimesOutOnSilenceWithTheConfiguredTimeout() = runBlocking {
        silence(2_000)
        val c = controller(FakeTranscriber())
        withTimeout(10_000) { c.run() }
        assertEquals(EndReason.SILENCE, (c.state.value as ConversationState.Ended).reason)
        assertTrue(agent.asked.isEmpty())
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun aCarMicThatSendsOnlyZerosSaysSo() = runBlocking {
        mic.feedMs(2_000, 0)
        val transcriber = FakeTranscriber()
        val c = controller(transcriber)
        withTimeout(10_000) { c.run() }
        val ended = c.state.value as ConversationState.Ended
        assertEquals(EndReason.ERROR, ended.reason)
        assertEquals(MicLevels.SILENT_MESSAGE, ended.message)
        assertTrue(transcriber.received.isEmpty())
    }

    @Test
    fun firstTurnSendsAudioTheGateMissedButFollowUpsDoNot() = runBlocking {
        mic.feedMs(2_000, 300) // a voice under the gate on the first question
        mic.feedMs(2_000, 300) // the same level on the follow-up: nobody answered
        val transcriber = FakeTranscriber("find fuel")
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val c = ConversationController(
            mic, transcriber, agent, speech, focus,
            ConversationConfig(endpoint = endpoint.copy(sendUnconfirmed = true), silenceTimeoutMs = 1_000),
            onState = { states += it },
            log = { lines += it },
        )
        withTimeout(10_000) { c.run() }
        assertEquals(listOf("find fuel"), agent.asked)
        assertEquals(1, transcriber.received.size)
        assertEquals(EndReason.SILENCE, (c.state.value as ConversationState.Ended).reason)
        assertTrue(lines.any { it.startsWith("turn 1: utterance outcome=unconfirmed-speech") })
        assertTrue(lines.any { it.startsWith("turn 2: utterance outcome=no-speech") })
        assertTrue(lines.last().startsWith("ended reason=SILENCE"))
    }

    @Test
    fun hostEndingTheMicEndsTheConversation() = runBlocking {
        utterance()
        mic.endSession()
        val c = controller(FakeTranscriber("hello"))
        withTimeout(10_000) { c.run() }
        val ended = c.state.value as ConversationState.Ended
        assertEquals(EndReason.HOST_ENDED, ended.reason)
        assertEquals("You said hello.", ended.lastReply)
    }

    @Test
    fun bargeInWhileSpeakingStopsTheReplyAndListens() = runBlocking {
        speech.holdUntilStopped = true
        utterance()
        val c = controller(FakeTranscriber("tell me a story", "stop"))
        val job = async(Dispatchers.IO) { c.run() }
        assertTrue(speech.speaking.await(5, TimeUnit.SECONDS))
        assertTrue(c.state.value is ConversationState.Speaking)
        speech.holdUntilStopped = false
        c.onMicTapped() // barge-in
        withTimeout(5_000) { while (c.state.value !is ConversationState.Listening) delay(5) }
        utterance() // the follow-up: "stop"
        withTimeout(10_000) { job.await() }
        assertEquals(1, speech.stops)
        assertEquals(listOf("tell me a story"), agent.asked)
        assertEquals(EndReason.STOP_PHRASE, (c.state.value as ConversationState.Ended).reason)
        assertEquals(listOf("Listening", "Thinking", "Thinking", "Speaking", "Listening", "Thinking", "Speaking", "Ended"), kinds())
    }

    @Test
    fun tapWhileListeningFinishesTheUtteranceNow() = runBlocking {
        mic.feedMs(100, 100)
        mic.feedMs(400, 3_000) // speaking, no trailing silence yet
        val c = controller(FakeTranscriber("hi"), ConversationConfig(continuous = false, endpoint = endpoint))
        val job = async(Dispatchers.IO) { c.run() }
        delay(300)
        assertTrue(c.state.value is ConversationState.Listening)
        c.onMicTapped()
        mic.feed(ByteArray(0)) // wake the read
        withTimeout(10_000) { job.await() }
        assertEquals(listOf("hi"), agent.asked)
        assertEquals(EndReason.TURN_COMPLETE, (c.state.value as ConversationState.Ended).reason)
    }

    @Test
    fun tapWhileListeningToNothingIsAStop() = runBlocking {
        mic.feedMs(100, 100)
        val c = controller(FakeTranscriber())
        val job = async(Dispatchers.IO) { c.run() }
        delay(300)
        c.onMicTapped()
        mic.feed(ByteArray(0))
        withTimeout(10_000) { job.await() }
        assertEquals(EndReason.USER_STOPPED, (c.state.value as ConversationState.Ended).reason)
    }

    @Test
    fun stopEndsFromAnyState() = runBlocking {
        speech.holdUntilStopped = true
        utterance()
        val c = controller(FakeTranscriber("hello"))
        val job = async(Dispatchers.IO) { c.run() }
        assertTrue(speech.speaking.await(5, TimeUnit.SECONDS))
        c.stop()
        withTimeout(5_000) { job.await() }
        assertEquals(EndReason.USER_STOPPED, (c.state.value as ConversationState.Ended).reason)
    }

    @Test
    fun oneTurnOnlyWhenNotContinuous() = runBlocking {
        utterance()
        val c = controller(FakeTranscriber("hello"), ConversationConfig(continuous = false, endpoint = endpoint))
        withTimeout(10_000) { c.run() }
        assertEquals(EndReason.TURN_COMPLETE, (c.state.value as ConversationState.Ended).reason)
        assertEquals(listOf("Listening", "Thinking", "Thinking", "Speaking", "Ended"), kinds())
        assertEquals(1, mic.started)
    }

    @Test
    fun emptyTranscriptListensOnceMoreThenGivesUp() = runBlocking {
        utterance()
        utterance()
        val c = controller(FakeTranscriber("", ""))
        withTimeout(10_000) { c.run() }
        val ended = c.state.value as ConversationState.Ended
        assertEquals(EndReason.ERROR, ended.reason)
        assertEquals("Didn't catch that.", ended.message)
        assertTrue(agent.asked.isEmpty())
        assertEquals(2, mic.started)
    }

    @Test
    fun emptyTranscriptInOneTurnModeFailsAtOnce() = runBlocking {
        utterance()
        val c = controller(FakeTranscriber(""), ConversationConfig(continuous = false, endpoint = endpoint, speakErrors = false))
        withTimeout(10_000) { c.run() }
        val ended = c.state.value as ConversationState.Ended
        assertEquals(EndReason.ERROR, ended.reason)
        assertEquals("Didn't catch that.", ended.message)
        assertEquals(1, mic.started)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun agentFailureIsSpokenThenEnds() = runBlocking {
        utterance()
        agent.failWith = AgentException("Agent error 502: upstream down")
        val c = controller(FakeTranscriber("hello"))
        withTimeout(10_000) { c.run() }
        val ended = c.state.value as ConversationState.Ended
        assertEquals(EndReason.ERROR, ended.reason)
        assertEquals("Agent error 502: upstream down", ended.message)
        assertEquals(listOf("Agent error 502: upstream down"), speech.spoken)
        assertEquals("hello", ended.lastHeard)
    }

    @Test
    fun transcriptionFailureEndsQuietlyWhenErrorsAreNotSpoken() = runBlocking {
        utterance()
        val t = FakeTranscriber().apply { failWith = AgentException("Transcription error 500") }
        val c = controller(t, ConversationConfig(endpoint = endpoint, speakErrors = false))
        withTimeout(10_000) { c.run() }
        assertEquals(EndReason.ERROR, (c.state.value as ConversationState.Ended).reason)
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun voiceFailureEndsWithItsMessage() = runBlocking {
        utterance()
        speech.failWith = "Voice error 401: bad key"
        val c = controller(FakeTranscriber("hello"))
        withTimeout(10_000) { c.run() }
        val ended = c.state.value as ConversationState.Ended
        assertEquals(EndReason.ERROR, ended.reason)
        assertEquals("Voice error 401: bad key", ended.message)
    }

    @Test
    fun turnCapEndsTheConversation() = runBlocking {
        repeat(3) { utterance() }
        val c = controller(FakeTranscriber("a", "b", "c"), ConversationConfig(endpoint = endpoint, maxTurns = 2))
        withTimeout(10_000) { c.run() }
        assertEquals(EndReason.TURN_COMPLETE, (c.state.value as ConversationState.Ended).reason)
        assertEquals(listOf("a", "b"), agent.asked)
    }

    @Test
    fun cancellingTheCallerStopsEverything() = runBlocking {
        speech.holdUntilStopped = true
        utterance()
        val c = controller(FakeTranscriber("hello"))
        val job = async(Dispatchers.IO) { c.run() }
        assertTrue(speech.speaking.await(5, TimeUnit.SECONDS))
        job.cancel()
        delay(100)
        assertTrue(speech.stops >= 1)
        assertFalse(focus.held)
    }

    @Test
    fun stopPhraseMatching() {
        for (yes in listOf("stop", "Stop.", "That's all", "that’s all, thanks", "okay never mind", "Nevermind!", "That is it.", "thanks, that's all", "cancel")) {
            assertTrue(yes, ConversationController.isStopPhrase(yes))
        }
        for (no in listOf("stop at the next gas station", "is the bus stop far", "never mind the weather, find food", "", "that's all right, keep going", "where is the stop sign")) {
            assertFalse(no, ConversationController.isStopPhrase(no))
        }
    }
}
