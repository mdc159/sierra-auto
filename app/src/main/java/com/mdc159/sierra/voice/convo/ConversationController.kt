package com.mdc159.sierra.voice.convo

import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.voice.stt.EndpointConfig
import com.mdc159.sierra.voice.stt.MicLevels
import com.mdc159.sierra.voice.stt.MicSource
import com.mdc159.sierra.voice.stt.Transcriber
import com.mdc159.sierra.voice.stt.UtteranceRecorder
import com.mdc159.sierra.voice.tts.AudioFocusGate
import com.mdc159.sierra.voice.tts.SpeakResult
import com.mdc159.sierra.voice.tts.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

/** The thing that answers. In production this wraps AgentClient plus the rolling Conversation. */
interface ConversationAgent {
    /** Returns the spoken reply for [userText]. Throws with a short message on failure. */
    suspend fun reply(userText: String): String
}

/** Tunables for one hands-free conversation. */
data class ConversationConfig(
    /** Keep listening after each reply. When false, one question and one answer. */
    val continuous: Boolean = true,
    /** Listening without hearing speech for this long ends the conversation. */
    val silenceTimeoutMs: Long = 8_000,
    /** Said on its own (or at the end of a short sentence), any of these ends the conversation. */
    val stopPhrases: List<String> = DEFAULT_STOP_PHRASES,
    /** Spoken when a stop phrase ends the conversation. Blank for silence. */
    val goodbye: String = "Okay.",
    /** Spoken when the agent or transcription fails, before the conversation ends. */
    val speakErrors: Boolean = true,
    /** Safety cap on turns in one conversation. */
    val maxTurns: Int = 30,
    /** End-of-speech tuning for the first utterance. Later turns use [silenceTimeoutMs] as the no-speech limit. */
    val endpoint: EndpointConfig = EndpointConfig(),
) {
    companion object {
        val DEFAULT_STOP_PHRASES = listOf(
            "stop", "that's all", "that is all", "thats all", "never mind", "nevermind",
            "that's it", "that is it", "cancel", "goodbye", "bye", "thanks that's all", "thank you that's all",
        )
    }
}

/** What the car screen shows. */
sealed class ConversationState {
    data object Idle : ConversationState()

    /** [turn] starts at 1. [heardSoFar] is the previous exchange, if any, so the screen can keep showing it. */
    data class Listening(val turn: Int, val lastReply: String? = null) : ConversationState()

    /** Transcribing, or waiting for the agent. [heard] is null until transcription completes. */
    data class Thinking(val turn: Int, val heard: String?) : ConversationState()

    data class Speaking(val turn: Int, val heard: String, val reply: String) : ConversationState()

    data class Ended(val reason: EndReason, val message: String? = null, val lastHeard: String? = null, val lastReply: String? = null) :
        ConversationState()
}

enum class EndReason {
    /** The driver said a stop phrase. */
    STOP_PHRASE,

    /** Nobody spoke for [ConversationConfig.silenceTimeoutMs]. */
    SILENCE,

    /** The host ended the microphone session (mic dismissed on the car screen). */
    HOST_ENDED,

    /** [ConversationController.stop] was called, or the driver tapped while nothing had been said. */
    USER_STOPPED,

    /** Not in continuous mode (or the turn cap was reached): one exchange is done. */
    TURN_COMPLETE,

    /** Transcription, the agent, or the voice failed; see the message. */
    ERROR,
}

/**
 * Hands-free conversation: listening → thinking → speaking → listening again, with no
 * tap between turns. Everything it touches is an interface so it runs the same against
 * fakes in unit tests and against the car microphone, the gateway and a network voice.
 *
 * Call [run] from a lifecycle-bound coroutine; it returns when the conversation has ended
 * and [state] is [ConversationState.Ended]. Cancelling that coroutine stops the microphone
 * and the voice. [onMicTapped] is barge-in while speaking and "done" while listening.
 */
class ConversationController(
    private val mic: MicSource,
    private val transcriber: Transcriber,
    private val agent: ConversationAgent,
    private val speech: SpeechOutput,
    private val focus: AudioFocusGate,
    private val config: ConversationConfig = ConversationConfig(),
    private val onState: (ConversationState) -> Unit = {},
    private val onExchange: (heard: String, reply: String) -> Unit = { _, _ -> },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Diagnostic lines (levels and outcomes, never audio or text); the car screen sends them to logcat. */
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<ConversationState>(ConversationState.Idle)
    val state: StateFlow<ConversationState> get() = _state

    private val stopRequested = AtomicBoolean(false)
    private val bargeIn = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var recorder: UtteranceRecorder? = null

    /**
     * The cabin noise floor measured on the last utterance (NaN until one was recorded).
     * Later turns, and the caller's next conversation, seed their detector with it.
     */
    @Volatile
    var lastNoiseFloor: Double = config.endpoint.initialNoiseFloor
        private set

    private fun set(s: ConversationState) {
        _state.value = s
        onState(s)
    }

    /** Ends the conversation as soon as the current step notices. Safe from any thread. */
    fun stop() {
        stopRequested.set(true)
        recorder?.finishNow()
        speech.stop()
    }

    /** The mic button: interrupt the reply and listen (barge-in), or finish the utterance now. */
    fun onMicTapped() {
        when (_state.value) {
            is ConversationState.Speaking -> {
                bargeIn.set(true)
                speech.stop()
            }
            is ConversationState.Listening -> recorder?.finishNow()
            else -> Unit
        }
    }

    /** Runs the conversation to its end. Can be called once. */
    suspend fun run() {
        check(started.compareAndSet(false, true)) { "ConversationController.run() can only be called once" }
        var turn = 0
        var lastHeard: String? = null
        var lastReply: String? = null
        var emptyTranscripts = 0
        try {
            while (true) {
                if (stopRequested.get()) return end(EndReason.USER_STOPPED, null, lastHeard, lastReply)
                turn++
                if (turn > config.maxTurns) return end(EndReason.TURN_COMPLETE, null, lastHeard, lastReply)
                set(ConversationState.Listening(turn, lastReply))

                val endpoint = (
                    if (turn == 1) {
                        config.endpoint
                    } else {
                        // A follow-up nobody answers must end the conversation, so never send unconfirmed audio here.
                        config.endpoint.copy(noSpeechMs = config.silenceTimeoutMs, sendUnconfirmed = false)
                    }
                    ).copy(initialNoiseFloor = lastNoiseFloor)
                val rec = UtteranceRecorder(mic, focus, endpoint, log = { log("turn $turn: $it") }).also { recorder = it }
                if (stopRequested.get()) rec.finishNow()
                val outcome = try {
                    runInterruptible(ioDispatcher) { rec.record() }
                } finally {
                    recorder = null
                    rec.lastDetector?.noiseFloor?.takeIf { it != Double.MAX_VALUE }?.let { lastNoiseFloor = it }
                }
                if (stopRequested.get()) return end(EndReason.USER_STOPPED, null, lastHeard, lastReply)

                val speechOutcome = when (outcome) {
                    is UtteranceRecorder.Outcome.HostEnded -> return end(EndReason.HOST_ENDED, null, lastHeard, lastReply)
                    is UtteranceRecorder.Outcome.FocusDenied ->
                        return end(EndReason.ERROR, "Couldn't get the microphone. Try again.", lastHeard, lastReply)
                    is UtteranceRecorder.Outcome.Failed -> return end(EndReason.ERROR, outcome.message, lastHeard, lastReply)
                    is UtteranceRecorder.Outcome.Silent -> return end(EndReason.ERROR, MicLevels.SILENT_MESSAGE, lastHeard, lastReply)
                    is UtteranceRecorder.Outcome.NoSpeech ->
                        return end(if (outcome.finishedByUser) EndReason.USER_STOPPED else EndReason.SILENCE, null, lastHeard, lastReply)
                    is UtteranceRecorder.Outcome.Speech -> outcome
                }

                set(ConversationState.Thinking(turn, null))
                val heard = try {
                    transcriber.transcribe(speechOutcome.pcm, speechOutcome.sampleRate).trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return fail(describe(e), lastHeard, lastReply)
                }
                if (stopRequested.get()) return end(EndReason.USER_STOPPED, null, lastHeard, lastReply)
                if (heard.isBlank()) {
                    // Noise that passed the energy gate but was not words. In a running
                    // conversation listen once more; otherwise say so and stop.
                    if (config.continuous && ++emptyTranscripts < 2) continue
                    return end(EndReason.ERROR, "Didn't catch that.", lastHeard, lastReply)
                }
                emptyTranscripts = 0

                if (isStopPhrase(heard, config.stopPhrases)) {
                    if (config.goodbye.isNotBlank()) {
                        set(ConversationState.Speaking(turn, heard, config.goodbye))
                        speech.speak(config.goodbye)
                    }
                    return end(EndReason.STOP_PHRASE, null, heard, lastReply)
                }

                set(ConversationState.Thinking(turn, heard))
                val reply = try {
                    agent.reply(heard).trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return fail(describe(e), heard, lastReply)
                }
                if (stopRequested.get()) return end(EndReason.USER_STOPPED, null, heard, reply)
                lastHeard = heard
                lastReply = reply
                onExchange(heard, reply)

                set(ConversationState.Speaking(turn, heard, reply))
                bargeIn.set(false)
                val result = speech.speak(reply)
                if (stopRequested.get()) return end(EndReason.USER_STOPPED, null, heard, reply)
                when (result) {
                    is SpeakResult.Failed -> return end(EndReason.ERROR, result.message, heard, reply)
                    SpeakResult.Interrupted -> if (!bargeIn.get()) return end(EndReason.USER_STOPPED, null, heard, reply)
                    SpeakResult.Completed -> if (!config.continuous) return end(EndReason.TURN_COMPLETE, null, heard, reply)
                }
            }
        } catch (e: CancellationException) {
            recorder?.finishNow()
            speech.stop()
            throw e
        }
    }

    private suspend fun fail(message: String, lastHeard: String?, lastReply: String?) {
        if (config.speakErrors && !stopRequested.get()) {
            set(ConversationState.Speaking(0, lastHeard.orEmpty(), message))
            speech.speak(message)
        }
        end(EndReason.ERROR, message, lastHeard, lastReply)
    }

    private fun end(reason: EndReason, message: String?, lastHeard: String?, lastReply: String?) {
        // Provider error bodies can echo request text or credentials; never send them to logcat.
        log("ended reason=$reason")
        set(ConversationState.Ended(reason, message, lastHeard, lastReply))
    }

    private fun describe(e: Exception): String = when (e) {
        is AgentException -> e.message ?: "Something went wrong."
        else -> Http.describe(e)
    }

    companion object {
        private val NOISE = Regex("[^a-z0-9' ]+")
        private val FILLERS = setOf("ok", "okay", "um", "uh", "oh", "sierra", "hey", "please", "thanks", "thank", "you", "alright", "yeah", "yes", "no")

        /**
         * True when [text] is a stop phrase: exactly one of [phrases] once punctuation and
         * case are dropped, or one of them wrapped in a couple of filler words
         * ("okay, that's all, thanks").
         */
        fun isStopPhrase(text: String, phrases: List<String> = ConversationConfig.DEFAULT_STOP_PHRASES): Boolean {
            val norm = normalize(text)
            if (norm.isEmpty()) return false
            val normalizedPhrases = phrases.map { normalize(it) }.filter { it.isNotEmpty() }
            if (norm in normalizedPhrases) return true
            val words = norm.split(' ')
            if (words.size > 6) return false
            for (phrase in normalizedPhrases) {
                val idx = norm.indexOf(phrase)
                if (idx < 0) continue
                // Whole-word match only ("stop" must not match "stopwatch" or "bus stop").
                val before = norm.substring(0, idx).trim()
                val after = norm.substring(idx + phrase.length).trim()
                val boundaryOk = (before.isEmpty() || norm[idx - 1] == ' ') && (after.isEmpty() || norm[idx + phrase.length] == ' ')
                if (!boundaryOk) continue
                val extras = (before.split(' ') + after.split(' ')).filter { it.isNotEmpty() }
                if (extras.all { it in FILLERS }) return true
            }
            return false
        }

        fun normalize(text: String): String =
            text.lowercase()
                .replace('’', '\'')
                .replace(NOISE, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
