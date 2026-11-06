package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.voice.Pcm16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicLevelsTest {

    @Test
    fun zerosForASecondAreDigitalSilence() {
        val levels = MicLevels()
        repeat(50) { levels.offer(Pcm16.silence(20), 640) }
        assertEquals(1_000L, levels.durationMs)
        assertTrue(levels.isDigitalSilence)
        assertEquals(0.0, levels.peakRms, 0.0)
    }

    @Test
    fun tooShortToCallSilence() {
        val levels = MicLevels()
        repeat(10) { levels.offer(Pcm16.silence(20), 640) }
        assertFalse(levels.isDigitalSilence)
    }

    @Test
    fun aQuietButLiveMicIsNotSilence() {
        val levels = MicLevels()
        repeat(60) { levels.offer(Pcm16.tone(20, 30), 640) }
        assertFalse(levels.isDigitalSilence)
        assertEquals(30.0, levels.peakRms, 0.5)
        assertEquals(30.0, levels.meanRms, 0.5)
        assertEquals(30, levels.maxAbsSample)
    }

    @Test
    fun tracksPeakQuietestAndMean() {
        val levels = MicLevels()
        levels.offer(Pcm16.tone(20, 100), 640)
        levels.offer(Pcm16.tone(20, 3_000), 640)
        assertEquals(3_000.0, levels.peakRms, 0.5)
        assertEquals(100.0, levels.quietestRms, 0.5)
        assertTrue(levels.meanRms in 2_000.0..2_200.0) // sqrt((100² + 3000²) / 2)
        assertTrue(levels.summary().contains("silent=false"))
    }
}
