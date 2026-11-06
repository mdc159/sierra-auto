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

    @Test fun doneUnblocksNativeReadWithoutAnotherAudioPacket() {
        val blocked = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val nativeMic = object : MicSource {
            override val sampleRate = 16000
            var sent = false
            override fun start() {}
            override fun read(buf: ByteArray, offset: Int, length: Int): Int {
                if (!sent) {
                    sent = true
                    for (i in offset until offset + length step 2) { buf[i] = 0; buf[i + 1] = 16 }
                    return length
                }
                blocked.countDown()
                check(stopped.await(2, TimeUnit.SECONDS))
                return -1
            }
            override fun stop() { stopped.countDown() }
        }
        val focus = FakeAudioFocus()
        val recorder = UtteranceRecorder(nativeMic, focus, EndpointConfig(), bufferSize = 16000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<UtteranceRecorder.Outcome> { recorder.record() }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            recorder.finishNow()
            val outcome = future.get(2, TimeUnit.SECONDS)
            assertTrue("Done submits captured speech, not a host cancellation", outcome is UtteranceRecorder.Outcome.Speech)
            assertEquals(1, focus.abandons)
        } finally { recorder.finishNow(); pool.shutdownNow() }
    }

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
    fun minusOneBeforeAnyAudioMeansTheHostRefusedTheMic() {
        // CarAudioRecord answers a refused openMicrophone with an immediate -1 (Assistant up,
        // call in progress, DHU `mic reject on`). That is a failure to retry, not a dismissal.
        mic.endSession()
        assertEquals(UtteranceRecorder.Outcome.Failed("Car microphone unavailable. Try again."), recorder().record())
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

    @Test
    fun zerosFromTheCarAreReportedAsSilenceNotAsNoSpeech() {
        mic.feedMs(2_500, 0)
        assertEquals(UtteranceRecorder.Outcome.Silent, recorder().record())
        assertEquals(1, focus.abandons)
    }

    @Test
    fun aQuietVoiceTheGateMissesGoesToTheTranscriberWhenAllowed() {
        // Speech below the absolute gate (a quiet head-unit mic, or road noise masking it).
        mic.feedMs(2_500, 300)
        val lines = mutableListOf<String>()
        val r = UtteranceRecorder(mic, focus, config.copy(sendUnconfirmed = true), bufferSize = 640, log = { lines += it })
        val outcome = r.record() as UtteranceRecorder.Outcome.Speech
        assertFalse(outcome.confirmed)
        assertEquals(2_000L, outcome.durationMs)
        assertTrue(lines.single().contains("outcome=unconfirmed-speech"))
        assertTrue(lines.single().contains("verdict=NO_SPEECH"))
    }

    @Test
    fun withoutPermissionAQuietRecordingIsStillNoSpeech() {
        mic.feedMs(2_500, 300)
        assertEquals(UtteranceRecorder.Outcome.NoSpeech(finishedByUser = false), recorder().record())
    }

    @Test
    fun doneAfterHalfASecondSendsWhatWasRecordedEvenIfTheGateHeardNothing() {
        val r = recorder()
        mic.feedMs(600, 300)
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<UtteranceRecorder.Outcome> { r.record() }
        Thread.sleep(150)
        r.finishNow()
        mic.feed(ByteArray(0))
        val outcome = future.get(5, TimeUnit.SECONDS) as UtteranceRecorder.Outcome.Speech
        assertFalse(outcome.confirmed)
        assertEquals(600L, outcome.durationMs)
        pool.shutdownNow()
    }

    @Test
    fun logsOneLineWithLevelsAndNoAudio() {
        mic.feedMs(200, 100)
        mic.feedMs(600, 3_000)
        mic.feedMs(1_000, 100)
        val lines = mutableListOf<String>()
        UtteranceRecorder(mic, focus, config, bufferSize = 640, log = { lines += it }).record()
        val line = lines.single()
        assertTrue(line, line.startsWith("utterance outcome=speech verdict=END_OF_SPEECH heardSpeech=true"))
        assertTrue(line, line.contains("peakRms=3000.0"))
        assertTrue(line, line.contains("threshold="))
    }
}
