package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.voice.CarMic
import com.mdc159.sierra.voice.duplex.AndroidFocus
import com.mdc159.sierra.voice.duplex.AudioTrackSink
import com.mdc159.sierra.voice.duplex.CarMicSource
import com.mdc159.sierra.voice.duplex.Duplex
import com.mdc159.sierra.voice.duplex.DuplexConfigStore
import com.mdc159.sierra.voice.duplex.DuplexController
import com.mdc159.sierra.voice.duplex.DuplexEvent
import com.mdc159.sierra.voice.duplex.DuplexException
import com.mdc159.sierra.voice.duplex.TalkToolAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lane J: a full-duplex conversation on the car screen. Replaces the one-shot Talk
 * screen when the driver taps "Conversation". The template text never changes while the
 * session runs (a MessageTemplate text change is a template step, constraints doc §4),
 * so the screen costs one step and shows at most one more when it ends with an error.
 */
class ConversationScreen(carContext: CarContext) : Screen(carContext) {

    private var controller: DuplexController? = null

    @Volatile
    private var failure: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                controller?.stop()
                controller = null
            }
        })
        begin()
    }

    private fun begin() {
        // Built inside the Talk screen's click listener: a store that cannot be opened (#11, #28)
        // must end in a message here, not in an exception the host treats as an app crash.
        val config = try {
            DuplexConfigStore.load(carContext)
        } catch (e: Exception) {
            failure = "Full-duplex settings are unavailable. Open Sierra on the phone."
            return
        }
        when {
            !config.isConfigured -> failure = "Set up full-duplex voice in Sierra on the phone."
            !CarMic.isSupported(carContext) -> failure = "This car does not offer its microphone to apps."
            !CarMic.hasPermission(carContext) -> failure = "Allow the microphone in Sierra on the phone."
        }
        if (failure != null) return
        val tools = Duplex.tools(carContext, TalkToolAdapter.adapt(TalkTools.forCar(carContext)))
        val c = DuplexController(
            session = Duplex.session(config, tools),
            mic = CarMicSource(carContext),
            sink = AudioTrackSink(),
            focus = AndroidFocus(carContext),
            mode = config.mode,
            guardRatio = config.guardRatio,
            listener = { event -> if (event is DuplexEvent.Ended) lifecycleScope.launch { onEnded(event.reason) } },
        )
        controller = c
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { c.start() }
            } catch (e: DuplexException) {
                controller = null
                failure = e.message ?: "Couldn't start the conversation."
                invalidate()
            }
        }
    }

    private fun onEnded(reason: String) {
        if (controller == null) return
        controller = null
        if (reason == "Stopped" || reason == "Ended") {
            screenManager.pop()
        } else {
            failure = reason
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val error = failure
        return if (error != null) {
            failedTemplate(error)
        } else {
            runningTemplate(onInterrupt = { controller?.tapInterrupt() }, onStop = { controller?.stop() })
        }
    }

    companion object {
        fun runningTemplate(onInterrupt: () -> Unit, onStop: () -> Unit): Template =
            MessageTemplate.Builder("Talk any time. Speak over Sierra to interrupt, or tap Interrupt.")
                .setTitle("Conversation")
                .setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle("Interrupt").setOnClickListener { onInterrupt() }.build())
                .addAction(
                    Action.Builder().setTitle("Stop").setFlags(Action.FLAG_PRIMARY).setOnClickListener { onStop() }.build(),
                )
                .build()

        fun failedTemplate(message: String): Template =
            MessageTemplate.Builder(message)
                .setTitle("Conversation")
                .setHeaderAction(Action.BACK)
                .build()
    }
}
