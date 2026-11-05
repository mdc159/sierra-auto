package com.mdc159.sierra.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM/WAV encoding and the speech endpointer, at the edges. */
class PcmTest {

    private class Wav(bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val riffSize = b.getInt(4)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        val fmt = String(bytes, 12, 4, Charsets.US_ASCII)
        val fmtSize = b.getInt(16)
        val format = b.getShort(20).toInt()
        val channels = b.getShort(22).toInt()
        val sampleRate = b.getInt(24)
        val byteRate = b.getInt(28)
        val blockAlign = b.getShort(32).toInt()
        val bitsPerSample = b.getShort(34).toInt()
        val data = String(bytes, 36, 4, Charsets.US_ASCII)
        val dataSize = b.getInt(40)
        val total = bytes.size
    }

    @Test
    fun wavHeaderDescribes16kHzMono16Bit() {
        val pcm = ByteArray(32_000) { it.toByte() }
        val w = Wav(Pcm.toWav(pcm))
        assertEquals("RIFF", w.riff)
        assertEquals(36 + 32_000, w.riffSize)
        assertEquals("WAVE", w.wave)
        assertEquals("fmt ", w.fmt)
        assertEquals(16, w.fmtSize)
        assertEquals(1, w.format) // PCM
        assertEquals(1, w.channels)
        assertEquals(16_000, w.sampleRate)
        assertEquals(32_000, w.byteRate)
        assertEquals(2, w.blockAlign)
        assertEquals(16, w.bitsPerSample)
        assertEquals("data", w.data)
        assertEquals(32_000, w.dataSize)
        assertEquals(44 + 32_000, w.total)
    }

    @Test
    fun wavPayloadIsCopiedVerbatim() {
        val pcm = ByteArray(1_001) { (it * 7).toByte() }
        val wav = Pcm.toWav(pcm)
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun wavOfEmptyPcmIsJustAHeader() {
        val w = Wav(Pcm.toWav(ByteArray(0)))
        assertEquals(44, w.total)
        assertEquals(36, w.riffSize)
        assertEquals(0, w.dataSize)
    }

    @Test
    fun wavHonoursSampleRateAndChannels() {
        val w = Wav(Pcm.toWav(ByteArray(8), sampleRate = 44_100, channels = 2))
        assertEquals(44_100, w.sampleRate)
        assertEquals(2, w.channels)
        assertEquals(44_100 * 2 * 2, w.byteRate)
        assertEquals(4, w.blockAlign)
    }

    @Test
    fun rmsHandlesEmptyOddAndFullScaleInput() {
        assertEquals(0.0, Pcm.rms(ByteArray(0), 0), 0.0)
        assertEquals(0.0, Pcm.rms(ByteArray(1), 1), 0.0) // a lone byte is not a sample
        val max = ByteArray(8) { if (it % 2 == 0) 0xFF.toByte() else 0x7F } // +32767
        assertEquals(32767.0, Pcm.rms(max, max.size), 0.001)
        val min = ByteArray(8) { if (it % 2 == 0) 0x00 else 0x80.toByte() } // -32768
        assertEquals(32768.0, Pcm.rms(min, min.size), 0.001)
        // Only the first `length` bytes count.
        assertEquals(32768.0, Pcm.rms(min + ByteArray(100), min.size), 0.001)
        // An odd length ignores the trailing byte rather than reading past it.
        assertEquals(32768.0, Pcm.rms(min + byteArrayOf(0x7F), min.size + 1), 0.001)
    }

    @Test
    fun rmsIsSignIndependent() {
        val pos = ByteArray(64) { if (it % 2 == 0) 0xE8.toByte() else 0x03 } // +1000
        val neg = ByteArray(64) { if (it % 2 == 0) 0x18 else 0xFC.toByte() } // -1000
        assertEquals(1000.0, Pcm.rms(pos, 64), 0.001)
        assertEquals(1000.0, Pcm.rms(neg, 64), 0.001)
    }

    @Test
    fun endpointerStopsAtTheHardCapEvenDuringSpeech() {
        val e = SpeechEndpointer(silenceMs = 1_400, noSpeechMs = 7_000, maxMs = 1_000)
        var stops = 0
        var chunks = 0
        while (stops == 0 && chunks < 1_000) {
            chunks++
            if (e.offer(3_000.0, 16)) stops++
        }
        assertEquals(1, stops)
        assertEquals(63, chunks) // 63 * 16 ms = 1008 ms >= 1000 ms
        assertTrue(e.heardSpeech)
    }

    @Test
    fun endpointerIgnoresQuietBumpsBelowTheFloorMultiple() {
        val e = SpeechEndpointer(silenceMs = 300, noSpeechMs = 2_000, maxMs = 10_000)
        repeat(10) { e.offer(400.0, 32) } // cabin noise: floor 400, threshold 1000
        repeat(10) { assertFalse(e.offer(900.0, 32)) } // louder road noise, still not speech
        assertFalse(e.heardSpeech)
        assertFalse(e.offer(1_000.0, 32)) // exactly the threshold counts as speech
        assertTrue(e.heardSpeech)
    }

    @Test
    fun endpointerThresholdNeverDropsBelowMinimum() {
        val e = SpeechEndpointer(silenceMs = 300, noSpeechMs = 2_000, maxMs = 10_000, minSpeechRms = 500.0)
        repeat(10) { e.offer(0.0, 32) } // digital silence: floor 0, threshold clamps to 500
        e.offer(499.0, 32)
        assertFalse(e.heardSpeech)
        e.offer(500.0, 32)
        assertTrue(e.heardSpeech)
    }

    @Test
    fun endpointerZeroLengthChunksNeverFinish() {
        val e = SpeechEndpointer(silenceMs = 10, noSpeechMs = 10, maxMs = 10)
        repeat(100) { assertFalse(e.offer(5_000.0, 0)) }
        assertTrue(e.heardSpeech)
        assertTrue(e.offer(5_000.0, 10))
    }

    @Test
    fun endpointerBriefPauseInsideSpeechDoesNotEndIt() {
        val e = SpeechEndpointer(silenceMs = 500, noSpeechMs = 7_000, maxMs = 15_000)
        repeat(20) { e.offer(3_000.0, 32) } // 640 ms speech
        repeat(10) { assertFalse(e.offer(50.0, 32)) } // 320 ms pause
        repeat(20) { assertFalse(e.offer(3_000.0, 32)) } // more speech
        var stopped = false
        repeat(20) { if (e.offer(50.0, 32)) stopped = true } // 640 ms silence
        assertTrue(stopped)
    }
}
