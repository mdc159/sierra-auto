package com.mdc159.sierra.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio focus as docs/android-auto-constraints.md section 7 describes it: exclusive
 * transient focus while the car microphone records (media pauses), ducking transient focus
 * while Sierra speaks. One gate holds at most one request at a time.
 */
interface AudioFocusGate {
    enum class Mode {
        /** Recording from the car microphone: AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE. */
        LISTEN,

        /** Speaking a reply: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK. */
        SPEAK,
    }

    /**
     * Requests focus. Returns false when the system refuses (for example during a phone
     * call). [onLoss] runs on the audio system's thread if focus is taken away later.
     */
    fun request(mode: Mode, onLoss: () -> Unit): Boolean

    /** Releases the focus held by the last successful [request], if any. Safe to call twice. */
    fun abandon()
}

/** Real [AudioFocusGate] backed by [AudioManager]. */
class AndroidAudioFocus(context: Context) : AudioFocusGate {

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var current: AudioFocusRequest? = null

    override fun request(mode: AudioFocusGate.Mode, onLoss: () -> Unit): Boolean {
        val gain = when (mode) {
            AudioFocusGate.Mode.LISTEN -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            AudioFocusGate.Mode.SPEAK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && mode == AudioFocusGate.Mode.LISTEN
                ) {
                    onLoss()
                }
            }
            .build()
        synchronized(this) {
            current?.let { audio.abandonAudioFocusRequest(it) }
            current = null
            val granted = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) current = request
            return granted
        }
    }

    override fun abandon() {
        synchronized(this) {
            current?.let { audio.abandonAudioFocusRequest(it) }
            current = null
        }
    }

    companion object {
        /** Assistant speech, not the navigation channel (quality rule DD-1). */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
