package com.mdc159.sierra.voice.stt

import kotlin.math.sqrt

/**
 * Tunable thresholds for [EndOfSpeechDetector]. All times are milliseconds of audio.
 *
 * @property silenceMs trailing quiet after speech that ends the utterance.
 * @property noSpeechMs how long to wait for the driver to start speaking before giving up.
 * @property maxMs hard cap on one utterance.
 * @property minSpeechMs sound above the threshold must last this long before it counts as
 *   speech, so a door thud or a cough does not start an utterance.
 * @property minSpeechRms absolute floor of the speech threshold, in 16-bit sample units.
 * @property speechToNoiseRatio how far above the noise floor speech must be.
 * @property maxThresholdRms the threshold used while no noise floor has been measured yet, so
 *   speech still registers when the very first chunks are already speech. Once a floor is
 *   known the threshold is `max(minSpeechRms, floor × ratio)` with no cap, so a loud cabin
 *   (highway speed, fan on high) cannot read as endless speech.
 * @property calibrationMs if nothing quieter than [maxThresholdRms] has been heard by then,
 *   the quietest chunk so far becomes the floor, so a cabin that is steadily louder than
 *   the cap ends an utterance after [silenceMs] instead of running to [maxMs].
 * @property initialNoiseFloor the floor measured on the previous turn (NaN for none). With
 *   it, a loud cabin is known from the first chunk and only a voice above it counts.
 * @property floorRiseMs time constant for the noise floor to creep up when the cabin gets
 *   louder while nobody is speaking.
 * @property sendUnconfirmed when the gate hears no speech before [noSpeechMs] but the audio is
 *   not digital silence, hand it to the transcriber anyway (used for the first question of a
 *   Talk, where the driver has just asked to speak). Off for ordinary follow-up turns (diagnostic mode can override), so a quiet cabin
 *   still ends a conversation.
 */
data class EndpointConfig(
    val silenceMs: Long = 1_200,
    val noSpeechMs: Long = 7_000,
    val maxMs: Long = 20_000,
    val minSpeechMs: Long = 150,
    val minSpeechRms: Double = 500.0,
    val speechToNoiseRatio: Double = 2.5,
    val maxThresholdRms: Double = 1_500.0,
    val calibrationMs: Long = 600,
    val initialNoiseFloor: Double = Double.NaN,
    val floorRiseMs: Long = 3_000,
    val sendUnconfirmed: Boolean = false,
)

/**
 * Energy-based end-of-speech detection on a PCM stream. Feed every chunk the microphone
 * returns; the verdict says whether to keep recording. Pure Kotlin so it can be tested with
 * synthetic audio.
 */
class EndOfSpeechDetector(
    private val config: EndpointConfig = EndpointConfig(),
    private val sampleRate: Int = 16_000,
) {
    enum class Verdict {
        /** Keep recording. */
        CONTINUE,

        /** The driver spoke and has now been quiet for [EndpointConfig.silenceMs]. */
        END_OF_SPEECH,

        /** Nothing that sounded like speech within [EndpointConfig.noSpeechMs]. */
        NO_SPEECH,

        /** [EndpointConfig.maxMs] reached; whatever was captured is the utterance. */
        MAX_LENGTH,
    }

    /** Milliseconds of audio seen so far. */
    var elapsedMs: Long = 0
        private set

    /** Current estimate of the cabin noise floor (RMS); MAX_VALUE until something quiet is heard. */
    var noiseFloor: Double = if (config.initialNoiseFloor.isNaN()) Double.MAX_VALUE else config.initialNoiseFloor
        private set

    /** Time at which real speech was first confirmed, or -1. */
    var speechStartMs: Long = -1
        private set

    private var lastLoudMs: Long = -1
    private var loudRunStartMs: Long = -1
    private var quietestRms: Double = Double.MAX_VALUE

    val heardSpeech: Boolean get() = speechStartMs >= 0

    /** True while the most recent chunk was above the speech threshold. */
    var isLoud: Boolean = false
        private set

    /** The RMS level above which a chunk counts as sound rather than cabin noise. */
    val threshold: Double
        get() = if (noiseFloor == Double.MAX_VALUE) {
            config.maxThresholdRms
        } else {
            maxOf(config.minSpeechRms, noiseFloor * config.speechToNoiseRatio)
        }

    /** Feeds [length] bytes of PCM. */
    fun feed(buf: ByteArray, length: Int): Verdict {
        val chunkMs = length * 1000L / (sampleRate * 2)
        return feed(rms(buf, length), chunkMs)
    }

    /** Feeds one chunk described by its RMS and duration. */
    fun feed(rms: Double, chunkMs: Long): Verdict {
        val before = elapsedMs
        elapsedMs += chunkMs
        quietestRms = minOf(quietestRms, rms)
        val loud = rms >= threshold
        isLoud = loud
        if (loud) {
            if (loudRunStartMs < 0) loudRunStartMs = before
            lastLoudMs = elapsedMs
            if (!heardSpeech && elapsedMs - loudRunStartMs >= config.minSpeechMs) speechStartMs = loudRunStartMs
        } else {
            loudRunStartMs = -1
            adaptFloor(rms, chunkMs)
        }
        if (noiseFloor == Double.MAX_VALUE && elapsedMs >= config.calibrationMs) noiseFloor = quietestRms
        return when {
            elapsedMs >= config.maxMs -> Verdict.MAX_LENGTH
            heardSpeech -> if (elapsedMs - lastLoudMs >= config.silenceMs) Verdict.END_OF_SPEECH else Verdict.CONTINUE
            elapsedMs >= config.noSpeechMs -> Verdict.NO_SPEECH
            else -> Verdict.CONTINUE
        }
    }

    private fun adaptFloor(rms: Double, chunkMs: Long) {
        if (rms < noiseFloor) {
            noiseFloor = rms
            return
        }
        // Quiet-but-louder-than-floor: let the floor rise slowly toward it.
        if (config.floorRiseMs <= 0) return
        val alpha = (chunkMs.toDouble() / config.floorRiseMs).coerceIn(0.0, 1.0)
        noiseFloor += (rms - noiseFloor) * alpha
    }

    companion object {
        /** RMS of 16-bit little-endian samples. */
        fun rms(buf: ByteArray, length: Int): Double {
            val samples = length / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            return sqrt(sum / samples)
        }
    }
}
