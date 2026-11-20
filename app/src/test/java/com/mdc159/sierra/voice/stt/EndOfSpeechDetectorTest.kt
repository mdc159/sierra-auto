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

    /** Four "syllables": 200 ms loud, 40 ms dip, like real speech, not a steady tone. */
    private fun EndOfSpeechDetector.speakMs(ms: Int, loud: Int = 3_000, dip: Int = 800): Verdict {
        var v = Verdict.CONTINUE
        var left = ms
        while (left > 0) {
            v = feedMs(minOf(200, left), loud)
            left -= 200
            if (left > 0) {
                v = feedMs(minOf(40, left), dip)
                left -= 40
            }
        }
        return v
    }

    @Test
    fun speechInTheVeryFirstChunkStillCounts() {
        val d = EndOfSpeechDetector(config)
        assertEquals(Verdict.CONTINUE, d.speakMs(800))
        assertTrue(d.heardSpeech)
        assertEquals(0, d.speechStartMs)
        assertEquals(Verdict.END_OF_SPEECH, d.feedMs(600, 100))
    }

    @Test
    fun aLoudCabinIsNotEndlessSpeech() {
        // Highway with the fan on high: steady 1 800 RMS, above the uncalibrated threshold. With
        // no floor known yet the first chunks do read as speech, but once the calibration window
        // has passed the floor is 1 800, the threshold 4 500, and the "utterance" ends after
        // silenceMs of cabin noise instead of running to maxMs.
        val d = EndOfSpeechDetector(config.copy(calibrationMs = 600, maxMs = 20_000, noSpeechMs = 5_000))
        var v = Verdict.CONTINUE
        var ms = 0
        while (v == Verdict.CONTINUE && ms < 20_000) {
            v = d.feedMs(20, 1_800)
            ms += 20
        }
        assertEquals(Verdict.END_OF_SPEECH, v)
        assertEquals(600 + 600, ms) // calibration, then silenceMs
        assertEquals(1_800.0, d.noiseFloor, 0.01)
        assertEquals(4_500.0, d.threshold, 0.01) // 1800 × 2.5, no cap once a floor exists

        // The next turn is seeded with that floor: steady noise is silence, a voice above it is speech.
        val seeded = EndOfSpeechDetector(config.copy(initialNoiseFloor = 1_800.0, noSpeechMs = 5_000))
        assertEquals(Verdict.CONTINUE, seeded.feedMs(1_000, 1_800))
        assertFalse(seeded.heardSpeech)
        assertEquals(Verdict.CONTINUE, seeded.feedMs(1_000, 6_000)) // the driver talks over the noise
        assertTrue(seeded.heardSpeech)
        assertEquals(1_000, seeded.speechStartMs)
        assertEquals(Verdict.END_OF_SPEECH, seeded.feedMs(600, 1_800))
        assertEquals(2_600, seeded.elapsedMs)

        val quiet = EndOfSpeechDetector(config.copy(initialNoiseFloor = 1_800.0, noSpeechMs = 2_000))
        assertEquals(Verdict.NO_SPEECH, quiet.feedMs(2_000, 1_800)) // nobody ever spoke
        assertFalse(quiet.heardSpeech)

        // A seeded floor drops at once when the cabin turns out to be quieter.
        val parked = EndOfSpeechDetector(config.copy(initialNoiseFloor = 1_800.0))
        parked.feedMs(100, 100)
        assertEquals(100.0, parked.noiseFloor, 0.01)
        assertEquals(500.0, parked.threshold, 0.01)
    }

    @Test
    fun steadyLoudSpeechFromTheFirstChunkIsStillSpeech() {
        // Lane E's screen tests feed a steady 4 000 RMS tone: it must count as speech and end on silence.
        val d = EndOfSpeechDetector(config)
        assertEquals(Verdict.CONTINUE, d.feedMs(800, 4_000))
        assertTrue(d.heardSpeech)
        assertEquals(Verdict.END_OF_SPEECH, d.feedMs(600, 0))
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
        assertEquals(Verdict.MAX_LENGTH, d.speakMs(2_000))
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
        loud.feedMs(100, 1_000) // 1000 < 1500, so it is "quiet" and becomes the floor
        assertEquals(2_500.0, loud.threshold, 0.01) // 1000 × 2.5; the cap applies only before a floor exists
    }

    @Test
    fun rmsMatchesTheKnownAmplitude() {
        val tone = Pcm16.tone(100, 4_096)
        assertEquals(4_096.0, EndOfSpeechDetector.rms(tone, tone.size), 1.0)
        assertEquals(0.0, EndOfSpeechDetector.rms(Pcm16.silence(100), 3_200), 0.0)
        assertEquals(0.0, EndOfSpeechDetector.rms(ByteArray(1), 1), 0.0)
    }
}
