package com.mdc159.sierra.voice.duplex

import java.io.ByteArrayOutputStream

/**
 * Linear-interpolation resampler for 16-bit little-endian mono PCM. Stateful: the
 * fractional read position and the last input sample carry across calls, so a stream
 * fed in arbitrary chunk sizes comes out continuous.
 *
 * Linear interpolation is good enough for speech between 16 kHz and 24 kHz (no
 * frequency content above 8 kHz in a 16 kHz source). It is not a studio resampler.
 */
class Resampler(val fromRate: Int, val toRate: Int) {
    init {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
    }

    private var lastSample = 0
    private var hasLast = false

    /**
     * Position of the next output sample relative to index 0 of the next chunk, in units
     * of 1/[toRate] input samples. Integer, so chunking never changes the result.
     */
    private var rel = 0L

    val isIdentity: Boolean get() = fromRate == toRate

    /** Resamples [length] bytes of [pcm] from [offset]. Returns the converted PCM bytes. */
    fun process(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        if (isIdentity) return pcm.copyOfRange(offset, offset + length)
        val inSamples = length / 2
        if (inSamples == 0) return ByteArray(0)
        val out = ByteArrayOutputStream((inSamples * toRate / fromRate + 2) * 2)

        // Work on a virtual array: index -1 is the carried last sample, 0..inSamples-1 is this chunk.
        fun sampleAt(i: Int): Int {
            if (i < 0) return lastSample
            val b = offset + i * 2
            return ((pcm[b + 1].toInt() shl 8) or (pcm[b].toInt() and 0xFF)).toShort().toInt()
        }

        val start = if (hasLast) -1 else 0
        val last = (inSamples - 1).toLong() * toRate
        while (rel <= last) {
            val i0 = Math.floorDiv(rel, toRate.toLong()).toInt()
            val frac = rel - i0.toLong() * toRate // 0 until toRate
            val s0 = sampleAt(maxOf(i0, start))
            val s1 = if (i0 + 1 <= inSamples - 1) sampleAt(i0 + 1) else s0
            val v = (s0 + (s1 - s0).toLong() * frac / toRate).toInt().coerceIn(-32768, 32767)
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            rel += fromRate
        }
        // The last sample of this chunk becomes virtual index -1 of the next one.
        rel -= inSamples.toLong() * toRate
        lastSample = sampleAt(inSamples - 1)
        hasLast = true
        return out.toByteArray()
    }

    fun reset() {
        lastSample = 0
        hasLast = false
        rel = 0
    }

    companion object {
        fun durationMs(bytes: Int, sampleRate: Int): Long = bytes * 1000L / (sampleRate * 2)
        fun bytesForMs(ms: Long, sampleRate: Int): Int = (ms * sampleRate * 2 / 1000).toInt() and 1.inv()
    }
}
