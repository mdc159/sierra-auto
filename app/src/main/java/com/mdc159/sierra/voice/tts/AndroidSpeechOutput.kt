package com.mdc159.sierra.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** The part of [TextToSpeech] that [AndroidSpeechOutput] needs, so tests can fake the engine. */
interface TtsEngine {
    interface Listener {
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String)
        fun onStopped(utteranceId: String)
    }

    fun setListener(listener: Listener)

    /** Queues [text], flushing anything still queued. Returns false if the engine refused. */
    fun speak(text: String, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
}

/**
 * Android's built-in voice, the fallback when no network voice is configured or reachable.
 * Same focus behaviour as the network voices: transient ducking focus for the utterance.
 */
class AndroidSpeechOutput(
    private val focus: AudioFocusGate,
    engineFactory: (onInit: (Boolean) -> Unit) -> TtsEngine,
    private val initTimeoutMs: Long = 5_000,
) : SpeechOutput {

    constructor(context: Context, focus: AudioFocusGate = AndroidAudioFocus(context)) :
        this(focus, { onInit -> RealTtsEngine(context, onInit) })

    override val name: String get() = "Android voice"

    private val ready = CompletableDeferred<Boolean>()
    private val ids = AtomicInteger()
    private val current = AtomicReference<Pending?>()

    private class Pending(val id: String) {
        var result: ((SpeakResult) -> Unit)? = null
    }

    private val engine: TtsEngine = engineFactory { ok -> ready.complete(ok) }

    init {
        engine.setListener(object : TtsEngine.Listener {
            override fun onDone(utteranceId: String) = finish(utteranceId, SpeakResult.Completed)
            override fun onError(utteranceId: String) = finish(utteranceId, SpeakResult.Failed("Android voice failed."))
            override fun onStopped(utteranceId: String) = finish(utteranceId, SpeakResult.Interrupted)
        })
    }

    private fun finish(utteranceId: String, result: SpeakResult) {
        val pending = current.get() ?: return
        if (pending.id != utteranceId) return
        if (current.compareAndSet(pending, null)) pending.result?.invoke(result)
    }

    override suspend fun speak(text: String): SpeakResult {
        if (text.isBlank()) return SpeakResult.Completed
        val ok = withTimeoutOrNull(initTimeoutMs) { ready.await() } ?: false
        if (!ok) return SpeakResult.Failed("Android voice is not available.")
        if (current.get() != null) stop()
        val pending = Pending("sierra-${ids.incrementAndGet()}")
        current.set(pending)
        if (!focus.request(AudioFocusGate.Mode.SPEAK) { stop() }) {
            current.compareAndSet(pending, null)
            return SpeakResult.Failed("Audio is busy. Try again.")
        }
        try {
            return suspendCancellableCoroutine { cont ->
                pending.result = { r -> if (cont.isActive) cont.resume(r) }
                cont.invokeOnCancellation { stop() }
                if (!engine.speak(text, pending.id)) {
                    current.compareAndSet(pending, null)
                    if (cont.isActive) cont.resume(SpeakResult.Failed("Android voice refused to speak."))
                }
            }
        } finally {
            current.compareAndSet(pending, null)
            focus.abandon()
        }
    }

    override fun stop() {
        val pending = current.getAndSet(null)
        engine.stop()
        pending?.result?.invoke(SpeakResult.Interrupted)
    }

    override fun close() {
        stop()
        engine.shutdown()
    }
}

/** [TtsEngine] over the real [TextToSpeech]. */
class RealTtsEngine(context: Context, onInit: (Boolean) -> Unit) : TtsEngine {

    private val app = context.applicationContext
    private var listener: TtsEngine.Listener? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(app) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok && ::tts.isInitialized) {
                runCatching { tts.setAudioAttributes(AndroidAudioFocus.ATTRIBUTES) }
                runCatching { tts.language = Locale.getDefault() }
            }
            onInit(ok)
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                listener?.onDone(utteranceId.orEmpty())
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                listener?.onError(utteranceId.orEmpty())
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                listener?.onError(utteranceId.orEmpty())
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                listener?.onStopped(utteranceId.orEmpty())
            }
        })
    }

    override fun setListener(listener: TtsEngine.Listener) {
        this.listener = listener
    }

    override fun speak(text: String, utteranceId: String): Boolean =
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun shutdown() {
        runCatching { tts.shutdown() }
    }
}
