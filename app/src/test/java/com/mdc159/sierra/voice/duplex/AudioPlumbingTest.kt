package com.mdc159.sierra.voice.duplex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private fun pcm(samples: IntArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, s ->
        out[i * 2] = (s and 0xFF).toByte()
        out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}

private fun samples(pcm: ByteArray): IntArray =
    IntArray(pcm.size / 2) { ((pcm[it * 2 + 1].toInt() shl 8) or (pcm[it * 2].toInt() and 0xFF)).toShort().toInt() }

private fun sine(rate: Int, hz: Double, ms: Int, amplitude: Int = 10_000): ByteArray =
    pcm(IntArray(rate * ms / 1000) { (amplitude * sin(2 * PI * hz * it / rate)).toInt() })

class ResamplerTest {

    @Test
    fun identityCopies() {
        val r = Resampler(16_000, 16_000)
        val input = pcm(intArrayOf(1, 2, 3))
        assertArrayEquals(input, r.process(input))
        assertTrue(r.isIdentity)
    }

    @Test
    fun upsamplingProducesTheRightLength() {
        val out = Resampler(16_000, 24_000).process(sine(16_000, 440.0, 1000))
        assertEquals(24_000.0, out.size / 2.0, 2.0)
    }

    @Test
    fun downsamplingProducesTheRightLength() {
        val out = Resampler(24_000, 16_000).process(sine(24_000, 440.0, 1000))
        assertEquals(16_000.0, out.size / 2.0, 2.0)
    }

    @Test
    fun constantSignalStaysConstant() {
        val out = samples(Resampler(16_000, 24_000).process(pcm(IntArray(160) { 1234 })))
        assertTrue(out.all { it == 1234 })
    }

    @Test
    fun chunkedInputMatchesWholeInput() {
        val whole = sine(16_000, 300.0, 500)
        val expected = Resampler(16_000, 24_000).process(whole)
        val chunked = Resampler(16_000, 24_000)
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        val sizes = intArrayOf(2, 640, 1282, 6, 500)
        var i = 0
        while (off < whole.size) {
            val n = minOf(sizes[i++ % sizes.size], whole.size - off)
            out.write(chunked.process(whole, off, n))
            off += n
        }
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun aToneSurvivesTheRoundTrip() {
        val original = samples(sine(16_000, 500.0, 200))
        val up = Resampler(16_000, 24_000).process(sine(16_000, 500.0, 200))
        val back = samples(Resampler(24_000, 16_000).process(up))
        // Compare the middle of the signal, allowing for interpolation error on a 500 Hz tone.
        var maxErr = 0
        for (i in 100 until minOf(original.size, back.size) - 100) maxErr = maxOf(maxErr, abs(original[i] - back[i]))
        assertTrue("max error $maxErr", maxErr < 600)
    }

    @Test
    fun durationHelpers() {
        assertEquals(1000L, Resampler.durationMs(48_000, 24_000))
        assertEquals(1280, Resampler.bytesForMs(40, 16_000))
        assertEquals(0, Resampler.bytesForMs(0, 16_000) % 2)
    }
}

class JitterBufferTest {

    @Test
    fun waitsForPrefillThenPlaysAndCountsPlayedMs() {
        val jb = JitterBuffer(sampleRate = 16_000, prefillMs = 100)
        jb.offer(ByteArray(1600)) // 50 ms
        val dest = ByteArray(640)
        assertEquals(0, jb.read(dest, timeoutMs = 60)) // not enough yet
        jb.offer(ByteArray(1600)) // 100 ms total
        assertEquals(640, jb.read(dest, timeoutMs = 60))
        assertEquals(20, jb.playedMs)
        assertEquals(80, jb.queuedMs)
    }

    @Test
    fun flushReleasesAShortReply() {
        val jb = JitterBuffer(16_000, prefillMs = 500)
        jb.offer(ByteArray(320))
        jb.flush()
        assertEquals(320, jb.read(ByteArray(640), timeoutMs = 60))
    }

    @Test
    fun clearDropsQueuedAudioAndResetsPlayed() {
        val jb = JitterBuffer(16_000, prefillMs = 0)
        jb.offer(ByteArray(3200))
        jb.read(ByteArray(640))
        assertEquals(20, jb.playedMs)
        jb.clear()
        assertTrue(jb.isEmpty)
        assertEquals(0, jb.playedMs)
        assertEquals(0, jb.read(ByteArray(640), timeoutMs = 30))
    }

    @Test
    fun readSpansChunks() {
        val jb = JitterBuffer(16_000, prefillMs = 0)
        jb.offer(byteArrayOf(1, 2, 3))
        jb.offer(byteArrayOf(4, 5, 6, 7))
        val dest = ByteArray(5)
        assertEquals(5, jb.read(dest))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), dest)
        assertEquals(2, jb.read(dest))
        assertEquals(6, dest[0].toInt())
    }

    @Test
    fun closeUnblocksReaders() {
        val jb = JitterBuffer(16_000)
        val t = Thread { jb.close() }
        t.start()
        assertEquals(-1, jb.read(ByteArray(10), timeoutMs = 2_000))
    }
}

class EchoGateTest {

    private var now = 10_000L
    private val loud = sine(16_000, 300.0, 40, amplitude = 8_000)
    private val quiet = sine(16_000, 300.0, 40, amplitude = 1_000)
    private val speakerLevel = sine(16_000, 200.0, 20, amplitude = 2_000)

    private fun gate(mode: DuplexMode) = EchoGate(mode, ratio = 2.0, hangoverMs = 250, floorRms = 400.0, clock = { now })

    @Test
    fun fullDuplexPassesEverything() {
        val g = gate(DuplexMode.FULL_DUPLEX)
        g.onPlayback(speakerLevel)
        assertArrayEquals(quiet, g.process(quiet))
        assertFalse(g.isGated)
    }

    @Test
    fun guardedPassesWhenNothingIsPlaying() {
        val g = gate(DuplexMode.GUARDED)
        assertArrayEquals(quiet, g.process(quiet))
        assertFalse(g.isGated)
    }

    @Test
    fun guardedSilencesEchoLevelInputWhilePlaying() {
        val g = gate(DuplexMode.GUARDED)
        g.onPlayback(speakerLevel)
        val out = g.process(quiet)
        assertTrue(g.isGated)
        assertEquals(quiet.size, out.size)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test
    fun guardedPassesTheDriverTalkingOverIt() {
        val g = gate(DuplexMode.GUARDED)
        g.onPlayback(speakerLevel) // RMS about 1414
        assertArrayEquals(loud, g.process(loud)) // RMS about 5657, more than 2x
        assertFalse(g.isGated)
    }

    @Test
    fun hangoverCoversTheSpeakerTailThenOpens() {
        val g = gate(DuplexMode.GUARDED)
        g.onPlayback(speakerLevel)
        now += 200
        g.process(quiet)
        assertTrue(g.isGated)
        now += 100 // 300 ms after the last playback chunk
        g.process(quiet)
        assertFalse(g.isGated)
    }

    @Test
    fun floorStopsNearSilenceFromCountingAsSpeech() {
        val g = gate(DuplexMode.GUARDED)
        g.onPlayback(sine(16_000, 200.0, 20, amplitude = 100)) // very quiet playback, RMS about 70
        g.process(sine(16_000, 300.0, 40, amplitude = 300)) // 3x louder but under the floor
        assertTrue(g.isGated)
    }

    @Test
    fun partialFramesAreHandled() {
        val g = gate(DuplexMode.GUARDED)
        g.onPlayback(speakerLevel)
        val out = g.process(quiet, length = 100)
        assertEquals(100, out.size)
    }
}
