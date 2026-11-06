package com.mdc159.sierra.voice.stt

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Running level statistics for one stretch of microphone audio (16-bit little-endian mono),
 * kept so a failed turn can be explained from `adb logcat -s SierraMic` afterwards, and so
 * a car that sends nothing but zeros can be told apart from a driver who said nothing.
 */
class MicLevels(private val sampleRate: Int = 16_000) {
    var bytes: Long = 0
        private set
    var chunks: Int = 0
        private set
    var peakRms: Double = 0.0
        private set
    var quietestRms: Double = Double.MAX_VALUE
        private set
    var maxAbsSample: Int = 0
        private set
    private var sumSquares = 0.0

    val durationMs: Long get() = bytes * 1000 / (sampleRate * 2)

    /** RMS over everything offered so far. */
    val meanRms: Double get() = if (bytes < 2) 0.0 else sqrt(sumSquares / (bytes / 2))

    /**
     * True once at least [SILENCE_MIN_MS] of audio has arrived and no sample was louder than
     * [SILENCE_MAX_SAMPLE]. A live microphone in a car always picks up some noise, so this is
     * the host or the phone handing over silence, not a quiet cabin.
     */
    val isDigitalSilence: Boolean get() = durationMs >= SILENCE_MIN_MS && maxAbsSample <= SILENCE_MAX_SAMPLE

    fun offer(buf: ByteArray, length: Int) {
        val samples = length / 2
        if (samples == 0) return
        var sum = 0.0
        var max = maxAbsSample
        var i = 0
        while (i + 1 < length) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            val a = abs(s)
            if (a > max) max = a
            i += 2
        }
        maxAbsSample = max
        sumSquares += sum
        bytes += samples * 2L
        chunks++
        val rms = sqrt(sum / samples)
        if (rms > peakRms) peakRms = rms
        if (rms < quietestRms) quietestRms = rms
    }

    /** One line for logcat: no audio content, only levels. */
    fun summary(): String = String.format(
        Locale.US,
        "ms=%d chunks=%d meanRms=%.1f peakRms=%.1f quietestRms=%.1f maxSample=%d silent=%b",
        durationMs, chunks, meanRms, peakRms, if (quietestRms == Double.MAX_VALUE) 0.0 else quietestRms,
        maxAbsSample, isDigitalSilence,
    )

    companion object {
        const val SILENCE_MIN_MS = 1_000L
        const val SILENCE_MAX_SAMPLE = 2

        /** Shown when the car hands over only silence. */
        const val SILENT_MESSAGE = "The car's microphone sent only silence. Check microphone access on the phone."
    }
}
