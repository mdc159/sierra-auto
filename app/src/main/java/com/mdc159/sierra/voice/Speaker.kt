package com.mdc159.sierra.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks agent replies with Android TextToSpeech. Takes transient audio focus (other
 * audio ducks) for each utterance and releases it when speech ends.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val ids = AtomicInteger()

    @Volatile
    private var ready = false

    @Volatile
    private var pending: String? = null

    private val tts = TextToSpeech(app, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setAudioAttributes(attributes)
        runCatching { tts.language = Locale.getDefault() }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = release()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = release()
            override fun onError(utteranceId: String?, errorCode: Int) = release()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = release()
        })
        ready = true
        pending?.let {
            pending = null
            speak(it)
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pending = text
            return
        }
        // If focus is denied (say, a phone call), stay quiet; the reply is on screen and
        // Repeat can speak it later.
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sierra-${ids.incrementAndGet()}")
    }

    fun stop() {
        pending = null
        if (ready) tts.stop()
        release()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun release() {
        audio.abandonAudioFocusRequest(focus)
    }
}
