package com.mdc159.sierra.phone

import com.mdc159.sierra.voice.FakeAudioFocus
import com.mdc159.sierra.voice.FakeMic
import com.mdc159.sierra.voice.Pcm16
import com.mdc159.sierra.voice.convo.VoiceSettings
import com.mdc159.sierra.voice.stt.UtteranceRecorder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVoiceConfigTest {
    @Test
    fun immediateQuietSpeechSurvivesCalibrationThroughFirstTurnFallback() {
        val firstWords = Pcm16.tone(800, 150)
        val mic = FakeMic().also {
            it.feed(firstWords) // no quiet prelude: calibration can mistake this for background
            it.feedMs(7_200, 6)
        }
        val result = UtteranceRecorder(
            mic, FakeAudioFocus(), VoiceSettings().phoneConversationConfig().endpoint,
        ).record()

        assertTrue(result is UtteranceRecorder.Outcome.Speech)
        result as UtteranceRecorder.Outcome.Speech
        assertFalse("the transcriber must get a turn even when local detection misses", result.confirmed)
        assertArrayEquals("the beginning of the question must be retained", firstWords, result.pcm.copyOf(firstWords.size))
    }
}
