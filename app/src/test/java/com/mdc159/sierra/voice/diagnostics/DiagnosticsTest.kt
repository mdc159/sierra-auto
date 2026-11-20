package com.mdc159.sierra.voice.diagnostics

import android.Manifest
import android.content.Context
import android.widget.Button
import android.widget.RadioGroup
import android.view.View
import android.view.ViewGroup
import com.mdc159.sierra.voice.duplex.MicSource
import com.mdc159.sierra.voice.duplex.DuplexException
import com.mdc159.sierra.car.MicTestScreen
import androidx.car.app.model.MessageTemplate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        VoiceDiagnostics.clear(context)
        context.getSharedPreferences("microphone_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun reportIsBoundedAndDoesNotIncludeRawFailureOrTranscript() {
        repeat(150) { VoiceDiagnostics.event(context, "stage", "event=$it") }
        VoiceDiagnostics.failure(context, "Transcription", java.io.IOException("Agent error 401: secret=super-private-transcript sk-abc https://secret/"))
        VoiceDiagnostics.recorder(context, "outcome=speech verdict=END_OF_SPEECH threshold=500.0 message=private-words")
        val report = VoiceDiagnostics.report(context)
        assertFalse(report.contains("event=0\n"))
        assertTrue(report.contains("event=149"))
        assertTrue(report.contains("HTTP 401"))
        assertTrue(report.contains("outcome=speech"))
        listOf("super-private", "sk-abc", "https://secret", "private-words").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.lines().size < 140)
    }
    @Test fun settingsPersistAndInvalidSourceFallsBack() {
        assertEquals(MicrophoneSource.AUTOMATIC, MicSettings.source(context))
        MicSettings.source(context, MicrophoneSource.PHONE)
        MicSettings.manual(context, true)
        assertEquals(MicrophoneSource.PHONE, MicSettings.source(context))
        assertTrue(MicSettings.manual(context))
        context.getSharedPreferences("microphone_settings", Context.MODE_PRIVATE).edit().putString("source", "bad").commit()
        assertEquals(MicrophoneSource.AUTOMATIC, MicSettings.source(context))
    }
    @Test fun microphoneLeaseRejectsOverlapAndReleasesAfterFailedStart() {
        val firstDevice = FakeMic()
        val secondDevice = FakeMic()
        val first = ObservedMic(context, "car", firstDevice)
        val second = ObservedMic(context, "phone", secondDevice)
        try {
            first.start()
            assertTrue(assertThrows(DuplexException::class.java) { second.start() }.message!!.contains("busy"))
            assertEquals(0, secondDevice.starts)
            first.stop()
            second.start()
            assertEquals(1, secondDevice.starts)
        } finally { first.stop(); second.stop() }
        val failed = ObservedMic(context, "car", FakeMic(fail = true))
        assertThrows(IllegalStateException::class.java) { failed.start() }
        val retry = ObservedMic(context, "car", FakeMic())
        try { retry.start() } finally { retry.stop() }
    }
    @Test fun stoppedMicCannotReopenAndPhoneNeedsExplicitBackgroundEnable() {
        val device = FakeMic()
        val mic = ObservedMic(context, "car", device)
        mic.stop()
        assertThrows(DuplexException::class.java) { mic.start() }
        assertEquals(0, device.starts)
        val phone = Microphones.phone(context, requireService = true)
        assertThrows(DuplexException::class.java) { phone.start() }
    }
    @Test fun activityHasSourceSelectorTestsAndShareWithoutComputerSetup() {
        val activity = Robolectric.buildActivity(MicDiagnosticsActivity::class.java).setup()
        try {
            val views = descendants(activity.get().window.decorView)
            assertEquals(3, views.filterIsInstance<RadioGroup>().single().childCount)
            val buttons = views.filterIsInstance<Button>().map { it.text.toString() }
            assertTrue(buttons.contains("Copy diagnostics"))
            assertTrue(buttons.contains("Share diagnostics"))
            assertTrue(buttons.contains("Test phone mic + transcription — 5 seconds"))
            assertFalse(PhoneMicService.ready)
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun carTestTemplatesRespectTwoActionLimit() {
        for (result in listOf(null, "Audio received. Details saved on the phone.")) {
            val template = MicTestScreen.template(result) {} as MessageTemplate
            assertEquals(1, template.actions.size)
            assertNotNull(template.headerAction)
        }
    }
    @Test fun stalledNativeReadIsStoppedAndLeaseReleased() {
        val released = java.util.concurrent.CountDownLatch(1)
        val device = object : MicSource {
            override val sampleRate = 16000
            override fun start() {}
            override fun read(dest: ByteArray): Int { check(released.await(3, java.util.concurrent.TimeUnit.SECONDS)); return -1 }
            override fun stop() { released.countDown() }
        }
        val mic = ObservedMic(context, "car", device, stallTimeoutMs = 50)
        try {
            mic.start()
            assertThrows(DuplexException::class.java) { mic.read(ByteArray(512)) }
            assertTrue(VoiceDiagnostics.report(context).contains("delivered no audio"))
        } finally { mic.stop() }
        val retry = ObservedMic(context, "car", FakeMic())
        try { retry.start() } finally { retry.stop() }
    }
    @Test fun disablingForegroundServiceClosesRegisteredPhoneMic() {
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication() as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val service = Robolectric.buildService(PhoneMicService::class.java).create().startCommand(0, 1)
        var destroyed = false
        var stopped = false
        val device = object : MicSource {
            override val sampleRate = 16000
            override fun start() {}
            override fun read(dest: ByteArray) = 0
            override fun stop() { stopped = true }
        }
        val mic = ObservedMic(context, "phone", device, phone = true, requireService = true)
        try {
            assertTrue(PhoneMicService.ready)
            mic.start()
            service.destroy()
            destroyed = true
            assertFalse(PhoneMicService.ready)
            assertTrue(stopped)
            assertFalse(VoiceDiagnostics.recording)
            assertThrows(DuplexException::class.java) { PhoneMicService.register(device, requireService = true) }
        } finally { mic.stop(); if (!destroyed) service.destroy() }
    }
    @Test fun talkAdapterReopensWithANewMicAfterAnInterruptedReply() {
        val devices = mutableListOf<FakeMic>()
        val adapter = Microphones.forTalk { ObservedMic(context, "car", FakeMic().also { devices += it }) }
        repeat(2) { try { adapter.start() } finally { adapter.stop() } }
        assertEquals(2, devices.size)
        assertEquals(listOf(1, 1), devices.map { it.starts })
    }
    @Test fun phoneBackgroundAccessRequiresAVisibleNotification() {
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication() as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val service = Robolectric.buildService(PhoneMicService::class.java).create().startCommand(0, 1)
        try { assertFalse(PhoneMicService.ready) } finally { service.destroy() }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private class FakeMic(private val fail: Boolean = false) : MicSource {
        override val sampleRate = 16000
        var starts = 0
        override fun start() { if (fail) throw IllegalStateException("open failed"); starts++ }
        override fun read(dest: ByteArray) = -1
        override fun stop() {}
    }
}
