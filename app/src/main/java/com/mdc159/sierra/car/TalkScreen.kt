package com.mdc159.sierra.car

import android.Manifest
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
import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.agent.Conversation
import com.mdc159.sierra.agent.History
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.voice.CarMic
import com.mdc159.sierra.voice.PhoneRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * One voice exchange: listen → think → reply. Each new question replaces this screen
 * with a fresh one, so the template step count never exceeds Home + 3.
 */
class TalkScreen(
    carContext: CarContext,
    private val session: SierraSession,
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

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                mic?.requestStop()
                job?.cancel()
            }
        })
        start(initial = true)
    }

    private fun setState(newState: State, initial: Boolean = false) {
        state = newState
        if (!initial) invalidate()
    }

    private fun start(initial: Boolean = false) {
        session.speaker.stop()
        val config = ConfigStore.load(carContext)
        if (!config.isConfigured) return setState(State.NotConfigured, initial)

        val useCarMic = config.hasTranscription && CarMic.isSupported(carContext)
        if (useCarMic && !CarMic.hasPermission(carContext)) return setState(State.NeedsMic, initial)

        setState(State.Listening(useCarMic), initial)
        val client = AgentClient(config)
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
                    client.chat(session.conversation.messagesFor(heard))
                }
                session.conversation.record(heard, reply)
                withContext(Dispatchers.IO) { runCatching { History.add(carContext, heard, reply) } }
                setState(State.Replied(heard, reply))
                session.speaker.speak(Conversation.forSpeech(reply))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentException) {
                setState(State.Failed(e.message ?: "Something went wrong."))
            } catch (e: Exception) {
                setState(State.Failed(Http.describe(e)))
            }
        }
    }

    private fun requestMic() {
        carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
            if (granted.isNotEmpty()) start() else invalidate()
        }
    }

    /** Starts a new question on a fresh screen so the step count resets. */
    private fun askAgain() {
        session.speaker.stop()
        screenManager.pop()
        screenManager.push(TalkScreen(carContext, session))
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
                        Action.Builder().setTitle("Done").setOnClickListener { mic?.requestStop() }.build()
                    )
                }
            }
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
            .build()
    }

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
                .setOnClickListener { session.speaker.speak(Conversation.forSpeech(reply)) }
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

    private fun micIcon(): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_mic)).build()
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
