package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.voice.tts.AudioFocusGate
import java.io.ByteArrayOutputStream

/**
 * Records one utterance from a [MicSource], ending it with [EndOfSpeechDetector] so the
 * driver never has to tap. Holds exclusive transient audio focus while recording and stops
 * on focus loss, exactly as the car-microphone guidance in the constraints doc requires.
 */
class UtteranceRecorder(
    private val mic: MicSource,
    private val focus: AudioFocusGate,
    private val config: EndpointConfig = EndpointConfig(),
    private val bufferSize: Int = 512,
) {
    sealed class Outcome {
        /** The driver said something. [pcm] is 16-bit mono at [sampleRate]. */
        data class Speech(val pcm: ByteArray, val sampleRate: Int, val durationMs: Long) : Outcome()

        /** Nothing heard. [finishedByUser] when the driver tapped to end listening early. */
        data class NoSpeech(val finishedByUser: Boolean) : Outcome()

        /** The host ended the microphone session (the driver dismissed the mic on screen). */
        data object HostEnded : Outcome()

        /** The system refused audio focus (for example a phone call). */
        data object FocusDenied : Outcome()

        /** The microphone could not be opened. */
        data class Failed(val message: String) : Outcome()
    }

    @Volatile
    private var finishRequested = false

    @Volatile
    private var focusLost = false

    @Volatile
    var lastDetector: EndOfSpeechDetector? = null
        private set

    /** Ends the utterance now with whatever has been captured (the "Done" tap). */
    fun finishNow() {
        finishRequested = true
    }

    /** Blocking; call off the main thread. */
    fun record(): Outcome {
        finishRequested = false
        focusLost = false
        val granted = focus.request(AudioFocusGate.Mode.LISTEN) {
            focusLost = true
            finishNow()
        }
        if (!granted) return Outcome.FocusDenied
        try {
            try {
                mic.start()
            } catch (e: Exception) {
                return Outcome.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Car microphone unavailable.")
            }
            val detector = EndOfSpeechDetector(config, mic.sampleRate).also { lastDetector = it }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(bufferSize)
            var verdict = EndOfSpeechDetector.Verdict.CONTINUE
            var hostEnded = false
            try {
                while (!finishRequested) {
                    val n = mic.read(buf, 0, buf.size)
                    if (n < 0) {
                        hostEnded = true
                        break
                    }
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    verdict = detector.feed(buf, n)
                    if (verdict != EndOfSpeechDetector.Verdict.CONTINUE) break
                }
            } finally {
                mic.stop()
            }
            if (hostEnded) {
                // CarAudioRecord (1.7.0) answers a refused openMicrophone (Assistant up, call in
                // progress, DHU `mic reject on`) with an immediate -1, before any audio flows. A real
                // dismissal always comes after the indicator was up and some bytes were read.
                if (out.size() == 0 && !finishRequested) return Outcome.Failed("Car microphone unavailable. Try again.")
                return Outcome.HostEnded
            }
            if (!detector.heardSpeech || verdict == EndOfSpeechDetector.Verdict.NO_SPEECH) {
                if (focusLost) return Outcome.FocusDenied
                return Outcome.NoSpeech(finishedByUser = finishRequested)
            }
            return Outcome.Speech(out.toByteArray(), mic.sampleRate, detector.elapsedMs)
        } finally {
            focus.abandon()
        }
    }
}
