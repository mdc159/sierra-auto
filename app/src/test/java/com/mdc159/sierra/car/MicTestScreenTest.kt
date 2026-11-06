package com.mdc159.sierra.car

import android.Manifest
import androidx.car.app.model.MessageTemplate
import com.mdc159.sierra.voice.diagnostics.VoiceDiagnostics
import com.mdc159.sierra.voice.Pcm
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MicTestScreenTest {
    @Test fun realCarHostReportsSignalAndReleasesMic() = checkAudio(2000, "Audio received.")
    @Test fun realCarHostDistinguishesDigitalSilenceFromMissingAudio() = checkAudio(0, "Microphone sent silence.")
    private fun checkAudio(amplitude: Int, expected: String) {
        val car = CarTestHost.carContext()
        CarTestHost.session(car)
        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
        val samples = ShortArray(80_000) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> bytes[i * 2] = s.toByte(); bytes[i * 2 + 1] = (s.toInt() shr 8).toByte() }
        car.fakeHost.setMicrophoneInputData(ByteArrayInputStream(bytes))
        val screen = MicTestScreen(car)
        CarTestHost.show(screen)
        CarTestHost.await(what = "local microphone test") {
            (screen.onGetTemplate() as MessageTemplate).actions.single().title.toString() == "Done"
        }
        assertTrue((screen.onGetTemplate() as MessageTemplate).message.toString().startsWith(expected))
        assertTrue(car.fakeHost.hasToldHostToStopRecording())
        assertFalse(VoiceDiagnostics.recording)
        assertTrue(VoiceDiagnostics.report(car).contains("Car mic test: completed"))
    }

    @Test fun missingPermissionIsActionableAndDoesNotStartRecording() {
        val car = CarTestHost.carContext()
        CarTestHost.session(car)
        CarTestHost.deny(Manifest.permission.RECORD_AUDIO)
        val screen = MicTestScreen(car)
        CarTestHost.show(screen)
        CarTestHost.await { (screen.onGetTemplate() as MessageTemplate).actions.single().title.toString() == "Done" }
        assertTrue((screen.onGetTemplate() as MessageTemplate).message.toString().contains("Allow the microphone"))
        assertFalse(VoiceDiagnostics.recording)
    }
}
