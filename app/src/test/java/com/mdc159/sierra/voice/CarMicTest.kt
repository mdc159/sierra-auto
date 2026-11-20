package com.mdc159.sierra.voice

import android.Manifest
import android.media.AudioManager
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.testing.TestCarContext
import androidx.car.app.versioning.CarAppApiLevels
import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.car.CarTestHost
import com.mdc159.sierra.car.TalkScreenTest
import com.mdc159.sierra.voice.stt.MicLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** Records from the Car App Library's fake car microphone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarMicTest {

    private lateinit var carContext: TestCarContext

    @Before
    fun setUp() {
        carContext = CarTestHost.carContext()
        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
    }

    private fun audio(): AudioManager = carContext.getSystemService(AudioManager::class.java)!!

    @Test
    fun recordsSpeechUntilSilenceAndReturnsAWav() {
        carContext.fakeHost.setMicrophoneInputData(
            ByteArrayInputStream(TalkScreenTest.speechThenSilence(speechMs = 800, silenceMs = 3_000))
        )
        val wav = CarMic(carContext).recordWav()!!
        assertEquals("RIFF", String(wav, 0, 4))
        val pcmBytes = wav.size - 44
        val ms = pcmBytes * 1000 / (CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE * 2)
        // 800 ms of speech plus ~1.4 s of trailing silence, well short of the 3.8 s supplied.
        assertTrue("recorded $ms ms", ms in 2_000..3_000)
        assertTrue(carContext.fakeHost.hasToldHostToStopRecording())
        // Focus was taken for the recording and released afterwards.
        assertNotNull(shadowOf(audio()).lastAudioFocusRequest)
        assertNotNull(shadowOf(audio()).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun hostClosingTheStreamIsADismissal() {
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(TalkScreenTest.speechThenSilence(200, 100)))
        assertNull(CarMic(carContext).recordWav())
        assertNotNull(shadowOf(audio()).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun hostWithNoMicrophoneDataIsADismissal() {
        // No input data set: the fake host hands over an already-closed descriptor.
        assertNull(CarMic(carContext).recordWav())
    }

    @Test
    fun silenceOnlyThrowsAReadableError() {
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(TalkScreenTest.speechThenSilence(0, 7_500)))
        val e = assertThrows(AgentException::class.java) { CarMic(carContext).recordWav() }
        assertEquals(MicLevels.SILENT_MESSAGE, e.message)
    }

    @Test
    fun aLiveButQuietMicIsDidntHearAnything() {
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(TalkScreenTest.steady(7_500, 100)))
        val e = assertThrows(AgentException::class.java) { CarMic(carContext).recordWav() }
        assertEquals("Didn't hear anything.", e.message)
    }

    @Test
    fun recordingStopsAtTheFifteenSecondCap() {
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(TalkScreenTest.speechThenSilence(20_000, 0)))
        val wav = CarMic(carContext).recordWav()!!
        val ms = (wav.size - 44) * 1000L / (CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE * 2)
        assertTrue("recorded $ms ms", ms in 15_000..15_100)
    }

    @Test
    fun requestStopBeforeAnyAudioThrowsNothingHeard() {
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(ByteArray(0)))
        val mic = CarMic(carContext)
        mic.requestStop()
        val e = assertThrows(AgentException::class.java) { mic.recordWav() }
        assertEquals("Didn't hear anything.", e.message)
    }

    @Test
    fun audioFocusDeniedThrowsWithoutOpeningTheMic() {
        shadowOf(audio()).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val e = assertThrows(AgentException::class.java) { CarMic(carContext).recordWav() }
        assertEquals("Couldn't get the microphone. Try again.", e.message)
        assertFalse(carContext.fakeHost.hasToldHostToStopRecording())
    }

    @Test
    fun missingPermissionThrowsBeforeTouchingTheHost() {
        CarTestHost.deny(Manifest.permission.RECORD_AUDIO)
        val e = assertThrows(AgentException::class.java) { CarMic(carContext).recordWav() }
        assertEquals("Allow the microphone on your phone.", e.message)
        assertFalse(carContext.fakeHost.hasToldHostToStopRecording())
    }

    @Test
    fun supportNeedsCarApi5() {
        assertFalse(CarMic.isSupported(CarTestHost.carContext(CarAppApiLevels.LEVEL_4)))
        assertTrue(CarMic.isSupported(CarTestHost.carContext(CarAppApiLevels.LEVEL_5)))
        assertTrue(CarMic.hasPermission(carContext))
        CarTestHost.deny(Manifest.permission.RECORD_AUDIO)
        assertFalse(CarMic.hasPermission(carContext))
    }
}
