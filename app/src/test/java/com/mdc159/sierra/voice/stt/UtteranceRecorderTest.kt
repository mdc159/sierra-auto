package com.mdc159.sierra.voice.stt

import com.mdc159.sierra.voice.FakeAudioFocus
import com.mdc159.sierra.voice.FakeMic
import com.mdc159.sierra.voice.tts.AudioFocusGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UtteranceRecorderTest {

    private val config = EndpointConfig(silenceMs = 400, noSpeechMs = 2_000, maxMs = 10_000, minSpeechMs = 100)
    private val mic = FakeMic()
    private val focus = FakeAudioFocus()

    private fun recorder() = UtteranceRecorder(mic, focus, config, bufferSize = 640)

    @Test
    fun capturesSpeechAndStopsOnTrailingSilence() {
        mic.feedMs(200, 100)
        mic.feedMs(600, 3_000)
        mic.feedMs(1_000, 100)
        val outcome = recorder().record() as UtteranceRecorder.Outcome.Speech
        assertEquals(16_000, outcome.sampleRate)
        assertEquals(200 + 600 + 400L, outcome.durationMs)
        assertEquals((1_200 * 32), outcome.pcm.size) // 16 kHz × 2 bytes = 32 bytes per ms
        assertEquals(listOf(AudioFocusGate.Mode.LISTEN), focus.requests)
        assertEquals(1, focus.abandons)
        assertEquals(1, mic.started)
        assertEquals(1, mic.stopped)
    }

    @Test
    fun noSpeechWhenTheCabinStaysQuiet() {
        mic.feedMs(2_500, 100)
        assertEquals(UtteranceRecorder.Outcome.NoSpeech(finishedByUser = false), recorder().record())
        assertEquals(1, focus.abandons)
    }

    @Test
    fun hostEndingTheSessionIsReported() {
        mic.feedMs(300, 3_000)
        mic.endSession()
        assertEquals(UtteranceRecorder.Outcome.HostEnded, recorder().record())
        assertEquals(1, mic.stopped)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun finishNowEndsEarlyWithWhatWasHeard() {
        val r = recorder()
        mic.feedMs(200, 100)
        mic.feedMs(300, 3_000)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<UtteranceRecorder.Outcome> { r.record() }
        Thread.sleep(150) // let it drain the queue and block on the empty mic
        r.finishNow()
        mic.feed(ByteArray(0)) // wake the blocked read
        val outcome = future.get(5, TimeUnit.SECONDS) as UtteranceRecorder.Outcome.Speech
        assertEquals(500L, outcome.durationMs)
        pool.shutdownNow()
    }

    @Test
    fun finishNowWithNothingHeardIsAUserStop() {
        val r = recorder()
        mic.feedMs(200, 100)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<UtteranceRecorder.Outcome> { r.record() }
        Thread.sleep(150)
        r.finishNow()
        mic.feed(ByteArray(0))
        assertEquals(UtteranceRecorder.Outcome.NoSpeech(finishedByUser = true), future.get(5, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun focusDeniedAndFocusLoss() {
        focus.grant = false
        assertEquals(UtteranceRecorder.Outcome.FocusDenied, recorder().record())
        assertEquals(0, mic.started)

        focus.grant = true
        val r = recorder()
        mic.feedMs(200, 100)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<UtteranceRecorder.Outcome> { r.record() }
        Thread.sleep(150)
        focus.loseFocus() // a phone call
        mic.feed(ByteArray(0))
        assertEquals(UtteranceRecorder.Outcome.FocusDenied, future.get(5, TimeUnit.SECONDS))
        assertFalse(focus.held)
        pool.shutdownNow()
    }

    @Test
    fun micFailureIsReportedAndFocusReleased() {
        mic.failStart = IllegalStateException("Car microphone unavailable.")
        assertEquals(UtteranceRecorder.Outcome.Failed("Car microphone unavailable."), recorder().record())
        assertTrue(focus.abandons == 1)
    }
}
