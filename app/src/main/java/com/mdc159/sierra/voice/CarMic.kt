package com.mdc159.sierra.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import com.mdc159.sierra.agent.AgentException
import java.io.ByteArrayOutputStream

/**
 * Records one utterance from the car's microphone via [CarAudioRecord].
 * Holds exclusive transient audio focus while recording (media pauses) and stops on
 * focus loss, when the driver stops talking, or when [requestStop] is called.
 */
class CarMic(private val carContext: CarContext) {

    @Volatile
    private var stopRequested = false

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Blocking; call off the main thread. Returns a WAV file, or null if the driver
     * dismissed the microphone (a cancel, so nothing is sent). Throws [AgentException].
     */
    fun recordWav(): ByteArray? {
        // CarAudioRecord requires RECORD_AUDIO; the screen checks first, but never rely on it.
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw AgentException("Allow the microphone on your phone.")
        }
        val audio = carContext.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    requestStop()
                }
            }
            .build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw AgentException("Couldn't get the microphone. Try again.")
        }
        try {
            val record = try {
                CarAudioRecord.create(carContext)
            } catch (e: Exception) {
                throw AgentException("Car microphone unavailable.", e)
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            val endpointer = SpeechEndpointer()
            var dismissed = false
            record.startRecording()
            try {
                while (!stopRequested) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) { // driver dismissed the mic on the car screen
                        dismissed = true
                        break
                    }
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    val chunkMs = n * 1000L / (Pcm.SAMPLE_RATE * 2)
                    if (endpointer.offer(Pcm.rms(buf, n), chunkMs)) break
                }
            } finally {
                record.stopRecording()
            }
            if (dismissed) return null
            if (!endpointer.heardSpeech && !stopRequested) throw AgentException("Didn't hear anything.")
            if (out.size() == 0) throw AgentException("Didn't hear anything.")
            return Pcm.toWav(out.toByteArray(), CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    companion object {
        fun isSupported(carContext: CarContext): Boolean =
            carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5

        fun hasPermission(carContext: CarContext): Boolean =
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}
