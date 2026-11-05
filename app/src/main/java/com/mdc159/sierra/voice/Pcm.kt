package com.mdc159.sierra.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** 16-bit little-endian mono PCM helpers. */
object Pcm {
    const val SAMPLE_RATE = 16_000

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

    /** Wraps raw PCM in a 44-byte RIFF/WAVE header. */
    fun toWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }
        return header.array() + pcm
    }
}

/**
 * Decides when the driver has finished speaking. It learns the cabin's noise floor
 * from the first chunks, treats anything well above it as speech, and ends after
 * [silenceMs] of quiet following speech, after [noSpeechMs] with no speech at all,
 * or at [maxMs].
 */
class SpeechEndpointer(
    private val silenceMs: Long = 1_400,
    private val noSpeechMs: Long = 7_000,
    private val maxMs: Long = 15_000,
    private val minSpeechRms: Double = 500.0,
) {
    private var elapsedMs = 0L
    private var noiseFloor = -1.0
    private var calibrationChunks = 0
    private var lastSpeechMs = -1L

    val heardSpeech: Boolean get() = lastSpeechMs >= 0

    /** Feeds one chunk; returns true when recording should stop. */
    fun offer(rms: Double, chunkMs: Long): Boolean {
        elapsedMs += chunkMs
        if (calibrationChunks < 5) {
            noiseFloor = if (noiseFloor < 0) rms else (noiseFloor * calibrationChunks + rms) / (calibrationChunks + 1)
            calibrationChunks++
        }
        val threshold = max(minSpeechRms, noiseFloor * 2.5)
        if (rms >= threshold) lastSpeechMs = elapsedMs
        return when {
            elapsedMs >= maxMs -> true
            heardSpeech -> elapsedMs - lastSpeechMs >= silenceMs
            else -> elapsedMs >= noSpeechMs
        }
    }
}
