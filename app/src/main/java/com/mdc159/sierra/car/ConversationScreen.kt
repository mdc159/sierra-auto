package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.studio54.Studio54
import com.mdc159.sierra.voice.CarMic
import com.mdc159.sierra.voice.MicLog
import com.mdc159.sierra.voice.diagnostics.MicSettings
import com.mdc159.sierra.voice.diagnostics.MicrophoneSource
import com.mdc159.sierra.voice.diagnostics.Microphones
import com.mdc159.sierra.voice.diagnostics.VoiceDiagnostics
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
            Studio54.duplexConfig(carContext, DuplexConfigStore.load(carContext))
        } catch (e: Exception) {
            failure = "Full-duplex settings are unavailable. Open Sierra on the phone."
            return
        }
        when {
            !config.isConfigured -> failure = "Set up full-duplex voice in Sierra on the phone."
            MicSettings.source(carContext) != MicrophoneSource.PHONE && !CarMic.isSupported(carContext) -> failure = "This car does not offer its microphone to apps. Select Phone microphone in Sierra."
            !CarMic.hasPermission(carContext) -> failure = "Allow the microphone in Sierra on the phone."
        }
        if (failure != null) return
        VoiceDiagnostics.event(carContext, "Conversation", "connecting; source=${MicSettings.source(carContext).label}; provider=${config.provider.name}")
        val input = try { Microphones.selected(carContext) } catch (e: Exception) {
            VoiceDiagnostics.failure(carContext, "Conversation microphone setup", e)
            failure = e.message ?: "Microphone unavailable. Open diagnostics on the phone."
            return
        }
        var audioSeen = false
        val tools = Duplex.tools(carContext, TalkToolAdapter.adapt(TalkTools.forCar(carContext)))
        val c = DuplexController(
            session = Duplex.session(config, tools),
            mic = input,
            sink = AudioTrackSink(),
            focus = AndroidFocus(carContext),
            mode = config.mode,
            guardRatio = config.guardRatio,
            listener = { event ->
                when (event) {
                    is DuplexEvent.Error -> {
                        VoiceDiagnostics.failure(carContext, "Voice connection error", DuplexException(event.message))
                        Studio54.onDuplexError(carContext, config.provider, event.message)
                    }
                    is DuplexEvent.Connected -> VoiceDiagnostics.event(carContext, "Conversation", "connected")
                    is DuplexEvent.Reconnecting -> VoiceDiagnostics.event(carContext, "Conversation", "reconnecting attempt=${event.attempt}")
                    is DuplexEvent.UserStartedSpeaking -> { audioSeen = false; VoiceDiagnostics.event(carContext, "Conversation", "server detected speech") }
                    is DuplexEvent.UserTranscript -> if (event.final) VoiceDiagnostics.event(carContext, "Conversation", "transcription received; characters=${event.text.length}")
                    is DuplexEvent.AssistantAudio -> if (!audioSeen) { audioSeen = true; VoiceDiagnostics.event(carContext, "Conversation", "reply audio received") }
                    is DuplexEvent.Ended -> {
                        VoiceDiagnostics.event(carContext, "Conversation", "ended")
                        lifecycleScope.launch { onEnded(event.reason) }
                    }
                    else -> Unit
                }
            },
            log = MicLog::i,
        )
        controller = c
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { c.start() }
            } catch (e: DuplexException) {
                VoiceDiagnostics.failure(carContext, "Conversation startup failed", e)
                Studio54.onDuplexError(carContext, config.provider, e.message)
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
