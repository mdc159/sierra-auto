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
    private val log: (String) -> Unit = {},
) {
    sealed class Outcome {
        /**
         * The driver said something. [pcm] is 16-bit mono at [sampleRate]. [confirmed] is false
         * when the energy gate never saw speech but the audio is sent anyway (a Done tap, or
         * [EndpointConfig.sendUnconfirmed]); the transcriber then decides.
         */
        data class Speech(val pcm: ByteArray, val sampleRate: Int, val durationMs: Long, val confirmed: Boolean = true) : Outcome()

        /** The microphone delivered only digital silence (see [MicLevels.isDigitalSilence]). */
        data object Silent : Outcome()

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
    private val micLock = Any()
    private var micStarted = false

    @Volatile
    var lastDetector: EndOfSpeechDetector? = null
        private set

    /** Ends the utterance now with whatever has been captured (the "Done" tap). */
    fun finishNow() {
        finishRequested = true
        synchronized(micLock) { if (micStarted) runCatching { mic.stop() } }
    }

    /** Blocking; call off the main thread. */
    fun record(): Outcome {
        if (finishRequested) return Outcome.NoSpeech(finishedByUser = true)
        focusLost = false
        val granted = focus.request(AudioFocusGate.Mode.LISTEN) {
            focusLost = true
            finishNow()
        }
        if (!granted) return Outcome.FocusDenied
        try {
            try {
                synchronized(micLock) {
                    if (finishRequested) return Outcome.NoSpeech(finishedByUser = true)
                    mic.start()
                    micStarted = true
                }
            } catch (e: Exception) {
                runCatching { mic.stop() }
                return Outcome.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Car microphone unavailable.")
            }
            val detector = EndOfSpeechDetector(config, mic.sampleRate).also { lastDetector = it }
            val levels = MicLevels(mic.sampleRate)
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
                    levels.offer(buf, n)
                    verdict = detector.feed(buf, n)
                    if (verdict != EndOfSpeechDetector.Verdict.CONTINUE) break
                }
            } finally {
                synchronized(micLock) { mic.stop(); micStarted = false }
            }
            val outcome = decide(hostEnded, verdict, detector, levels, out)
            log(
                "utterance outcome=${outcome.label()} verdict=$verdict heardSpeech=${detector.heardSpeech} " +
                    "finishedByUser=$finishRequested focusLost=$focusLost ${levels.summary()} " +
                    "floor=${fmt(detector.noiseFloor)} threshold=${fmt(detector.threshold)} " +
                    "initialFloor=${fmt(config.initialNoiseFloor)} sendUnconfirmed=${config.sendUnconfirmed}",
            )
            return outcome
        } finally {
            focus.abandon()
        }
    }

    private fun decide(
        hostEnded: Boolean,
        verdict: EndOfSpeechDetector.Verdict,
        detector: EndOfSpeechDetector,
        levels: MicLevels,
        out: ByteArrayOutputStream,
    ): Outcome {
        if (focusLost) return Outcome.FocusDenied
        if (hostEnded && !finishRequested) {
            // CarAudioRecord (1.7.0) answers a refused openMicrophone (Assistant up, call in
            // progress, DHU `mic reject on`) with an immediate -1, before any audio flows. A real
            // dismissal always comes after the indicator was up and some bytes were read.
            if (out.size() == 0 && !finishRequested) return Outcome.Failed("Car microphone unavailable. Try again.")
            return Outcome.HostEnded
        }
        if (detector.heardSpeech && verdict != EndOfSpeechDetector.Verdict.NO_SPEECH) {
            return Outcome.Speech(out.toByteArray(), mic.sampleRate, detector.elapsedMs)
        }
        if (focusLost) return Outcome.FocusDenied
        if (levels.isDigitalSilence) return Outcome.Silent
        // The energy gate heard nothing, but a road-noise floor or a quiet head-unit mic can hide
        // a voice from it. When the driver tapped Done, or the caller allows it (the first
        // question of a Talk), let the transcriber decide instead of dropping the audio.
        if ((finishRequested || config.sendUnconfirmed) && levels.durationMs >= MIN_UNCONFIRMED_MS) {
            return Outcome.Speech(out.toByteArray(), mic.sampleRate, levels.durationMs, confirmed = false)
        }
        return Outcome.NoSpeech(finishedByUser = finishRequested)
    }

    private fun Outcome.label(): String = when (this) {
        is Outcome.Speech -> if (confirmed) "speech" else "unconfirmed-speech"
        is Outcome.NoSpeech -> "no-speech"
        Outcome.Silent -> "silent"
        Outcome.HostEnded -> "host-ended"
        Outcome.FocusDenied -> "focus-denied"
        is Outcome.Failed -> "failed"
    }

    private fun fmt(v: Double): String = when {
        v.isNaN() -> "none"
        v == Double.MAX_VALUE -> "unset"
        else -> String.format(java.util.Locale.US, "%.1f", v)
    }

    companion object {
        /** Shortest unconfirmed recording worth sending to the transcriber. */
        const val MIN_UNCONFIRMED_MS = 300L
    }
}
