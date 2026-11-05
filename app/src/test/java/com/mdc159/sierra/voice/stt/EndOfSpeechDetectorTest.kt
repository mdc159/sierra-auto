package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.voice.Pcm16
import com.mdc159.sierra.voice.stt.EndOfSpeechDetector.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndOfSpeechDetectorTest {

    private val config = EndpointConfig(silenceMs = 600, noSpeechMs = 3_000, maxMs = 10_000, minSpeechMs = 100)

    /** Feeds [ms] of audio at [rms] in 20 ms chunks; returns the last verdict. */
    private fun EndOfSpeechDetector.feedMs(ms: Int, rms: Int, chunkMs: Int = 20): Verdict {
        var v = Verdict.CONTINUE
        var left = ms
        while (left > 0) {
            val m = minOf(chunkMs, left)
            v = feed(Pcm16.tone(m, rms), Pcm16.tone(m, rms).size)
            left -= m
        }
        return v
    }

    @Test
    fun endsAfterSilenceFollowingSpeech() {
        val d = EndOfSpeechDetector(config)
        assertEquals(Verdict.CONTINUE, d.feedMs(500, 120)) // cabin noise, calibrates the floor
        assertFalse(d.heardSpeech)
        assertEquals(Verdict.CONTINUE, d.feedMs(1_200, 3_000)) // speech
        assertTrue(d.heardSpeech)
        assertTrue(d.isLoud)
        assertEquals(500, d.speechStartMs)
        var v = Verdict.CONTINUE
        var quiet = 0
        while (v == Verdict.CONTINUE) {
            v = d.feedMs(20, 120)
            quiet += 20
        }
        assertEquals(Verdict.END_OF_SPEECH, v)
        assertEquals(600, quiet)
    }

    @Test
    fun givesUpWhenNobodySpeaks() {
        val d = EndOfSpeechDetector(config)
        assertEquals(Verdict.NO_SPEECH, d.feedMs(3_000, 100))
        assertFalse(d.heardSpeech)
    }

    @Test
    fun aShortThudIsNotSpeech() {
        val d = EndOfSpeechDetector(config)
        d.feedMs(400, 100)
        d.feedMs(40, 5_000) // a door closing: two loud chunks, under minSpeechMs
        assertFalse(d.heardSpeech)
        assertEquals(Verdict.NO_SPEECH, d.feedMs(2_600, 100))
    }

    @Test
    fun speechInTheVeryFirstChunkStillCounts() {
        val d = EndOfSpeechDetector(config)
        assertEquals(Verdict.CONTINUE, d.feedMs(800, 3_000))
        assertTrue(d.heardSpeech)
        assertEquals(0, d.speechStartMs)
        assertEquals(Verdict.END_OF_SPEECH, d.feedMs(600, 100))
    }

    @Test
    fun pausesShorterThanSilenceMsDoNotEndTheUtterance() {
        val d = EndOfSpeechDetector(config)
        d.feedMs(300, 100)
        d.feedMs(500, 3_000)
        assertEquals(Verdict.CONTINUE, d.feedMs(400, 100)) // breath
        assertEquals(Verdict.CONTINUE, d.feedMs(500, 3_000))
        assertEquals(Verdict.END_OF_SPEECH, d.feedMs(600, 100))
        assertEquals(300 + 500 + 400 + 500 + 600L, d.elapsedMs)
    }

    @Test
    fun hardCapEndsALongUtterance() {
        val d = EndOfSpeechDetector(config.copy(maxMs = 2_000))
        assertEquals(Verdict.MAX_LENGTH, d.feedMs(2_000, 3_000))
        assertTrue(d.heardSpeech)
    }

    @Test
    fun thresholdFollowsTheNoiseFloorWithinBounds() {
        val d = EndOfSpeechDetector(config.copy(minSpeechRms = 500.0, speechToNoiseRatio = 2.5, maxThresholdRms = 1_500.0))
        assertEquals(1_500.0, d.threshold, 0.01) // no floor yet
        d.feedMs(100, 100)
        assertEquals(500.0, d.threshold, 0.01) // 100 * 2.5 = 250, clamped up to the minimum
        d.feedMs(100, 400) // louder cabin, but quieter than the threshold: floor creeps up
        assertTrue(d.noiseFloor > 100.0)
        assertTrue(d.noiseFloor < 400.0)
        val loud = EndOfSpeechDetector(config.copy(floorRiseMs = 0))
        loud.feedMs(100, 1_000) // ≥ 1500? no, 1000 < 1500 so it is "quiet" and sets the floor
        assertEquals(1_500.0, loud.threshold, 0.01) // 1000 * 2.5 clamped to the cap
    }

    @Test
    fun rmsMatchesTheKnownAmplitude() {
        val tone = Pcm16.tone(100, 4_096)
        assertEquals(4_096.0, EndOfSpeechDetector.rms(tone, tone.size), 1.0)
        assertEquals(0.0, EndOfSpeechDetector.rms(Pcm16.silence(100), 3_200), 0.0)
        assertEquals(0.0, EndOfSpeechDetector.rms(ByteArray(1), 1), 0.0)
    }
}
