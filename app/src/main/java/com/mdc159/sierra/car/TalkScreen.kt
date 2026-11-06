package com.mdc159.sierra.car

import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.R
import com.mdc159.sierra.agent.AgentClient
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.agent.Conversation
import com.mdc159.sierra.agent.History
import com.mdc159.sierra.agent.ToolListener
import com.mdc159.sierra.agent.tools.FileIdeaTool
import com.mdc159.sierra.agent.tools.FindPlacesTool
import com.mdc159.sierra.agent.tools.LatLng
import com.mdc159.sierra.agent.tools.LocationProvider
import com.mdc159.sierra.agent.tools.NavigateToTool
import com.mdc159.sierra.agent.tools.Navigator
import com.mdc159.sierra.agent.tools.Tool
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.places.PhoneLocation
import com.mdc159.sierra.studio54.Studio54
import com.mdc159.sierra.voice.CarMic
import com.mdc159.sierra.voice.MicLog
import com.mdc159.sierra.voice.PhoneRecognizer
import com.mdc159.sierra.voice.convo.ConversationAgent
import com.mdc159.sierra.voice.convo.ConversationController
import com.mdc159.sierra.voice.convo.ConversationState
import com.mdc159.sierra.voice.convo.EndReason
import com.mdc159.sierra.voice.convo.VoiceSettings
import com.mdc159.sierra.voice.convo.VoiceSettingsStore
import com.mdc159.sierra.voice.convo.VoiceStack
import com.mdc159.sierra.voice.stt.CarMicSource
import com.mdc159.sierra.voice.stt.Transcriber
import com.mdc159.sierra.voice.tts.AndroidAudioFocus
import com.mdc159.sierra.voice.tts.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * One voice exchange: listen → think → reply. Each new question replaces this screen
 * with a fresh one, so the template step count never exceeds Home + 3.
 *
 * While the agent runs a tool, a [ToolStatusScreen] is pushed on top ("Finding fuel
 * nearby…") and removed when the tool ends. Pushing costs one template step and popping
 * refunds it (constraints doc §4), so the stack peaks at Home + 3 + 1 = 4 of the 5
 * allowed, no matter how many tools run, and the Thinking message underneath never
 * changes text (a Message text change would be a non-refundable step).
 *
 * Lane G: when the car microphone and a transcription endpoint are available, the turn runs
 * through [ConversationController] (energy end-of-speech, stop phrases, the chosen voice).
 * In conversation mode the screen then replaces itself with a follow-up screen that listens
 * at once, so the driver never taps between turns and the step count resets every turn.
 * [previous] is the exchange a follow-up screen shows again when the conversation ends.
 */
class TalkScreen(
    carContext: CarContext,
    private val session: SierraSession,
    private val tools: List<Tool> = TalkTools.forCar(carContext),
    private val previous: Pair<String, String>? = null,
) : Screen(carContext) {

    private sealed interface State {
        data object NotConfigured : State
        data object NeedsMic : State
        data class Listening(val carMic: Boolean) : State
        data object Thinking : State
        data class Replied(val heard: String, val reply: String) : State
        data class Failed(val message: String) : State
    }

    private var state: State = State.Thinking
    private var job: Job? = null
    private var mic: CarMic? = null
    private var statusScreen: Screen? = null
    private var controller: ConversationController? = null

    /** Shared per session (see [TalkVoice]); this screen stops it but never closes it. */
    private var speech: SpeechOutput? = null

    /** Called on the agent's worker thread; hops to the main thread before touching screens. */
    private val toolListener = object : ToolListener {
        override fun onToolStart(status: String) {
            lifecycleScope.launch { showToolStatus(status) }
        }

        override fun onToolEnd() {
            lifecycleScope.launch { hideToolStatus() }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                mic?.requestStop()
                controller?.stop()
                job?.cancel()
                statusScreen = null
            }
        })
        start(initial = true)
    }

    private fun showToolStatus(status: String) {
        hideToolStatus()
        // Only stack the status on top of this screen while it is the one showing.
        if (screenManager.top !== this) return
        val screen = ToolStatusScreen(carContext, status)
        statusScreen = screen
        screenManager.push(screen)
    }

    /** Removes the status screen wherever it is in the stack; a no-op if it is already gone. */
    private fun hideToolStatus() {
        val screen = statusScreen ?: return
        statusScreen = null
        runCatching { screenManager.remove(screen) }
    }

    private fun setState(newState: State, initial: Boolean = false) {
        if (newState == state) return
        state = newState
        if (!initial) invalidate()
    }

    private fun start(initial: Boolean = false) {
        session.speaker.stop()
        // Signed in to Studio 54: the gateway address and key come from the vault, the model from the phone.
        val config = Studio54.agentConfig(carContext, ConfigStore.load(carContext))
        if (!config.isConfigured) return setState(State.NotConfigured, initial)

        val useCarMic = config.hasTranscription && CarMic.isSupported(carContext)
        MicLog.i(
            "talk start carApiLevel=${carContext.carAppApiLevel} carMic=$useCarMic " +
                "micPermission=${CarMic.hasPermission(carContext)} followUp=${previous != null}",
        )
        if (useCarMic && !CarMic.hasPermission(carContext)) return setState(State.NeedsMic, initial)

        setState(State.Listening(useCarMic), initial)
        val client = AgentClient(config)
        if (useCarMic) {
            val voice = VoiceSettingsStore.load(carContext)
            val transcriber = VoiceStack.transcriber(voice, config)
            if (transcriber != null) return startConversation(config, client, voice, transcriber)
        }
        job = lifecycleScope.launch {
            try {
                val heard = if (useCarMic) {
                    val carMic = CarMic(carContext).also { mic = it }
                    val wav = withContext(Dispatchers.IO) { carMic.recordWav() }
                    if (wav == null) {
                        // Mic dismissed on the car screen: treat as cancel, send nothing.
                        screenManager.pop()
                        return@launch
                    }
                    setState(State.Thinking)
                    runInterruptible(Dispatchers.IO) { client.transcribe(wav) }
                } else {
                    PhoneRecognizer.recognize(carContext).also { setState(State.Thinking) }
                }
                if (heard.isBlank()) throw AgentException("Didn't catch that.")

                val reply = runInterruptible(Dispatchers.IO) {
                    client.chat(session.conversation.messagesFor(heard), tools, toolListener)
                }
                hideToolStatus()
                session.conversation.record(heard, reply)
                withContext(Dispatchers.IO) { runCatching { History.add(carContext, heard, reply) } }
                setState(State.Replied(heard, reply))
                session.speaker.speak(Conversation.forSpeech(reply))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentException) {
                hideToolStatus()
                Studio54.onAgentError(carContext, e)
                setState(State.Failed(e.message ?: "Something went wrong."))
            } catch (e: Exception) {
                hideToolStatus()
                setState(State.Failed(Http.describe(e)))
            }
        }
    }

    private fun requestMic() {
        carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
            if (granted.isNotEmpty()) start() else invalidate()
        }
    }

    /**
     * One turn through the conversation controller: car mic → end-of-speech → transcriber →
     * the tool-calling agent → the chosen voice. The controller runs a single turn; the
     * screen decides what follows (see [render]).
     */
    private fun startConversation(config: AgentConfig, client: AgentClient, voice: VoiceSettings, transcriber: Transcriber) {
        // Lane I's hook: a 401 from the gateway may mean the vault key was rotated.
        val watchedTranscriber = object : Transcriber {
            override suspend fun transcribe(pcm: ByteArray, sampleRate: Int): String = try {
                transcriber.transcribe(pcm, sampleRate)
            } catch (e: AgentException) {
                Studio54.onAgentError(carContext, e)
                throw e
            }
        }
        val agent = object : ConversationAgent {
            override suspend fun reply(userText: String): String {
                val reply = try {
                    runInterruptible(Dispatchers.IO) { client.chat(session.conversation.messagesFor(userText), tools, toolListener) }
                } catch (e: AgentException) {
                    Studio54.onAgentError(carContext, e)
                    throw e
                } finally {
                    hideToolStatus()
                }
                session.conversation.record(userText, reply)
                withContext(Dispatchers.IO) { runCatching { History.add(carContext, userText, reply) } }
                return Conversation.forSpeech(reply)
            }
        }
        val out = TalkVoice.speechFor(session, carContext, voice, config).also { speech = it }
        val base = voice.conversationConfig()
        val followUp = previous != null
        val controllerConfig = base.copy(
            continuous = false, // the screen chains turns itself, so each turn starts with a fresh step budget
            speakErrors = false, // errors are shown with Try again, as before
            endpoint = (if (followUp) base.endpoint.copy(noSpeechMs = base.silenceTimeoutMs) else base.endpoint)
                // The cabin as the last turn heard it. On the first question, a recording the energy
                // gate called empty still goes to the transcriber (road noise can hide a voice from it).
                .copy(initialNoiseFloor = TalkVoice.noiseFloor, sendUnconfirmed = !followUp),
        )
        val c = ConversationController(
            mic = CarMicSource(carContext),
            transcriber = watchedTranscriber,
            agent = agent,
            speech = out,
            focus = AndroidAudioFocus(carContext),
            config = controllerConfig,
            onState = { render(it, voice.conversationMode) }, // called on the main thread
            log = MicLog::i,
        )
        controller = c
        job = lifecycleScope.launch {
            try {
                c.run()
            } finally {
                TalkVoice.noiseFloor = c.lastNoiseFloor
            }
        }
    }

    private fun render(s: ConversationState, conversationMode: Boolean) {
        when (s) {
            is ConversationState.Idle, is ConversationState.Listening -> setState(State.Listening(carMic = true))
            is ConversationState.Thinking -> setState(State.Thinking)
            is ConversationState.Speaking -> setState(State.Replied(s.heard, s.reply))
            is ConversationState.Ended -> {
                val heard = s.lastHeard
                val reply = s.lastReply
                val shown = if (heard != null && reply != null) heard to reply else previous
                when (s.reason) {
                    EndReason.HOST_ENDED -> screenManager.pop() // mic dismissed on the car screen: a cancel
                    EndReason.ERROR -> setState(State.Failed(s.message ?: "Something went wrong."))
                    EndReason.TURN_COMPLETE -> {
                        if (shown == null) return screenManager.pop()
                        setState(State.Replied(shown.first, shown.second))
                        if (conversationMode) nextTurn(shown)
                    }
                    EndReason.STOP_PHRASE ->
                        if (shown != null) setState(State.Replied(shown.first, shown.second)) else screenManager.pop()
                    EndReason.SILENCE, EndReason.USER_STOPPED ->
                        if (shown != null) setState(State.Replied(shown.first, shown.second)) else setState(State.Failed("Didn't hear anything."))
                }
            }
        }
    }

    /** Conversation mode: Sierra has finished speaking, so listen again on a fresh screen, no tap needed. */
    private fun nextTurn(previous: Pair<String, String>) {
        if (screenManager.top !== this) return
        screenManager.pop()
        screenManager.push(TalkScreen(carContext, session, tools, previous))
    }

    /** Starts a new question on a fresh screen so the step count resets. */
    private fun askAgain() {
        controller?.stop()
        speech?.stop()
        session.speaker.stop()
        screenManager.pop()
        screenManager.push(TalkScreen(carContext, session, tools))
    }

    override fun onGetTemplate(): Template = when (val s = state) {
        State.NotConfigured -> message(
            "Set up your agent on the phone",
            "Open Sierra on the phone and enter an endpoint, key and model.",
        )
        State.NeedsMic -> MessageTemplate.Builder("Allow the microphone on your phone.")
            .setTitle("Sierra")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Allow")
                    .setFlags(Action.FLAG_PRIMARY)
                    .setOnClickListener { requestMic() }
                    .build()
            )
            .build()
        is State.Listening -> MessageTemplate.Builder("Listening…")
            .setTitle("Sierra")
            .setHeaderAction(Action.BACK)
            .setIcon(micIcon())
            .apply {
                if (s.carMic) {
                    addAction(
                        Action.Builder().setTitle("Done").setOnClickListener { finishListening() }.build()
                    )
                }
            }
            .addAction(conversationAction())
            .build()
        State.Thinking -> MessageTemplate.Builder("Thinking…")
            .setTitle("Sierra")
            .setHeaderAction(Action.BACK)
            .setLoading(true)
            .build()
        is State.Replied -> replyTemplate(s.heard, s.reply)
        is State.Failed -> MessageTemplate.Builder(s.message)
            .setTitle("Sierra")
            .setHeaderAction(Action.BACK)
            .addAction(askAgainAction("Try again"))
            .addAction(conversationAction())
            .build()
    }

    /** Lane J: swaps this one-shot exchange for the full-duplex ConversationScreen. */
    private fun conversationAction(): Action =
        Action.Builder()
            .setTitle("Conversation")
            .setOnClickListener {
                // Hand the car microphone and the audio focus back before the duplex session
                // asks for them: stop lane G's turn and voice the same way askAgain() does.
                mic?.requestStop()
                controller?.stop()
                speech?.stop()
                job?.cancel()
                session.speaker.stop()
                screenManager.pop()
                screenManager.push(ConversationScreen(carContext))
            }
            .build()

    private fun message(title: String, text: String): Template =
        MessageTemplate.Builder(text)
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()

    private fun replyTemplate(heard: String, reply: String): Template {
        val maxRows = CarLimits.listLimit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("You: " + Conversation.truncateForScreen(heard, 80)).build())
        ReplyLayout.chunks(reply, maxRows - 1).forEach { pane.addRow(Row.Builder().setTitle(it).build()) }
        pane.addAction(askAgainAction("Ask again"))
        pane.addAction(
            Action.Builder()
                .setTitle("Repeat")
                .setOnClickListener { repeat(reply) }
                .build()
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle("Sierra")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun askAgainAction(title: String): Action =
        Action.Builder()
            .setTitle(title)
            .setIcon(micIcon())
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener { askAgain() }
            .build()

    /** The Done button: end the utterance now (conversation path) or stop the one-shot recorder. */
    private fun finishListening() {
        val c = controller
        if (c != null) c.onMicTapped() else mic?.requestStop()
    }

    /** Repeat uses the same voice that read the reply; the old Speaker remains for the phone path. */
    private fun repeat(reply: String) {
        val out = speech
        if (out != null) {
            lifecycleScope.launch { out.speak(Conversation.forSpeech(reply)) }
        } else {
            session.speaker.speak(Conversation.forSpeech(reply))
        }
    }

    private fun micIcon(): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build()
}

/**
 * One [SpeechOutput] per car session, built from the voice settings and rebuilt when they
 * change, so the TTS engine or AudioTrack is not re-created on every turn. Closed when the
 * session is destroyed.
 */
object TalkVoice {
    private class Entry(val session: SierraSession, val settings: VoiceSettings, val agent: AgentConfig, val output: SpeechOutput)

    private var entry: Entry? = null

    /** Cabin noise floor from the last turn, so a loud cabin is known before the driver speaks. NaN until measured. */
    @Volatile
    var noiseFloor: Double = Double.NaN

    /** Main thread only. */
    fun speechFor(session: SierraSession, carContext: CarContext, settings: VoiceSettings, agent: AgentConfig): SpeechOutput {
        entry?.let { if (it.session === session && it.settings == settings && it.agent == agent) return it.output }
        entry?.output?.close()
        val output = VoiceStack.speechOutput(carContext, settings, agent)
        entry = Entry(session, settings, agent, output)
        session.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                val current = entry ?: return
                if (current.session === session) {
                    current.output.close()
                    entry = null
                    noiseFloor = Double.NaN
                }
            }
        })
        return output
    }
}

/**
 * A one-line "doing it" message shown on top of TalkScreen while a tool runs. BACK lets
 * the driver leave it early (the tool keeps running; TalkScreen's `hideToolStatus()`
 * tolerates a screen that is already gone). A blank status falls back to "Working…"
 * because MessageTemplate refuses an empty message.
 */
class ToolStatusScreen(carContext: CarContext, private val status: String) : Screen(carContext) {
    override fun onGetTemplate(): Template = template(status)

    companion object {
        fun template(status: String): Template =
            MessageTemplate.Builder(Conversation.truncateForScreen(status, 80).ifBlank { "Working…" })
                .setTitle("Sierra")
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
    }
}

/** Wires the agent's tools to this car session: phone location, the Maps hand-off, the idea token. */
object TalkTools {
    fun forCar(carContext: CarContext): List<Tool> = listOf(
        FindPlacesTool(
            LocationProvider {
                runBlocking { PhoneLocation.current(carContext) }?.let { LatLng(it.latitude, it.longitude) }
            },
        ),
        NavigateToTool(
            Navigator { name, lat, lng ->
                // startCarApp talks to the host; keep it on the main thread like the Places screen does.
                Handler(Looper.getMainLooper()).post {
                    if (lat != null && lng != null) {
                        NavHandoff.toPlace(carContext, lat, lng, name)
                    } else {
                        NavHandoff.search(carContext, name)
                    }
                }
            },
        ),
        FileIdeaTool(token = { ConfigStore.loadIdeaToken(carContext) }),
    )
}

/** Splits a reply into short rows for a PaneTemplate. */
object ReplyLayout {
    fun chunks(reply: String, maxRows: Int, rowChars: Int = 110): List<String> {
        val rows = maxRows.coerceAtLeast(1)
        val text = Conversation.truncateForScreen(reply, rows * rowChars)
        val sentences = text.split(Regex("(?<=[.!?…])\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + 1 + sentence.length > rowChars) {
                out += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) out += current.toString()
        if (out.size <= rows) return out
        val kept = out.take(rows).toMutableList()
        kept[rows - 1] = Conversation.truncateForScreen(out.drop(rows - 1).joinToString(" "), rowChars)
        return kept
    }
}
