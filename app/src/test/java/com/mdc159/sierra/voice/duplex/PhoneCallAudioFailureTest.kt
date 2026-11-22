package com.mdc159.sierra.voice.duplex

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * Hardening of PhoneCallAudio's exit paths: a session that fails part-way through
 * [PhoneCallAudio.request], or whose [PhoneCallAudio.abandon] hits a dying audio service,
 * must still put the mode, the route and the voice-call volume back where it found them.
 * Each test injects one failure through [FlakyOps] and asserts the rest of the snapshot.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneCallAudioFailureTest {

    private fun context() = RuntimeEnvironment.getApplication()
    private fun audio(): AudioManager = context().getSystemService(AudioManager::class.java)

    /** Delegates to the platform ops over the Robolectric AudioManager, but fails where told. */
    private class FlakyOps(private val real: CallAudioOps) : CallAudioOps by real {
        var failVolumeReads = false
        var failRouteQuery = false
        var failDeviceSet = false
        var failModeWrites = false

        /** Fails the [n]th voice-call volume read (1-based); 0 never fires. */
        var failVoiceCallReadNumber = 0
        private var voiceCallReads = 0

        override fun streamVolume(stream: Int): Int {
            if (failVolumeReads) throw RuntimeException("volume read failed")
            if (stream == AudioManager.STREAM_VOICE_CALL && ++voiceCallReads == failVoiceCallReadNumber) {
                throw RuntimeException("voice-call volume read $n failed")
            }
            return real.streamVolume(stream)
        }

        override fun devicesForAttributes(attributes: AudioAttributes): List<AudioDeviceInfo> {
            if (failRouteQuery) throw RuntimeException("route query failed")
            return real.devicesForAttributes(attributes)
        }

        override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
            if (failDeviceSet) throw RuntimeException("communication device set failed")
            return real.setCommunicationDevice(device)
        }

        override var mode: Int
            get() = real.mode
            set(value) {
                // Only the write back to NORMAL fails; setting up IN_COMMUNICATION must work.
                if (failModeWrites && value == AudioManager.MODE_NORMAL) throw RuntimeException("mode write failed")
                real.mode = value
            }

        private val n get() = voiceCallReads
    }

    private val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
    private val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()

    /** What matchAssistantVolume raises the 1/7 call volume to for the assistant stream at 5/7. */
    private val TARGET_VOLUME = 5

    /** The hidden STREAM_ASSISTANT (11) PhoneCallAudio matches the call volume against. */
    private val STREAM_ASSISTANT = 11

    /**
     * The state a session starts from: another app's Bluetooth routing, the assistant
     * stream at 5/7 and a voice-call volume (1/7) below the matching target (5), so the
     * session raises it and a restore has something to put back.
     */
    private fun installSnapshot() {
        val audio = audio()
        org.robolectric.Shadows.shadowOf(audio).setAvailableCommunicationDevices(listOf(headset, speaker))
        audio.setCommunicationDevice(headset)
        audio.setStreamVolume(STREAM_ASSISTANT, 5, 0)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 1, 0)
    }

    private fun flakyPhone(): Pair<PhoneCallAudio, FlakyOps> {
        val ops = FlakyOps(CallAudioOps.Platform(audio()))
        return PhoneCallAudio(context(), ops) to ops
    }

    /**
     * request can throw after it has already changed mode and route — here in the
     * loudspeaker wait's route query. Its own partial changes must be undone and the
     * exception rethrown, not left for the caller to notice while the phone stays in
     * communication mode on the loudspeaker.
     */
    @Test
    @Config(sdk = [35])
    fun requestFailureAtTheLoudspeakerWaitRestoresModeRouteAndVolume() {
        installSnapshot()
        val (phone, ops) = flakyPhone()
        ops.failRouteQuery = true

        try {
            phone.request {}
            fail("expected the route query failure to surface")
        } catch (expected: RuntimeException) {
        }

        val audio = audio()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audio.communicationDevice?.type)
        assertEquals("volume back at the snapshot", 1, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
    }

    /**
     * The summary log's volume getters run last, after focus, mode, route and volume are
     * all taken: read 1 is matchAssistantVolume's "before", read 2 its read-back, read 3
     * the log's. Failing there must undo everything, including the raised volume.
     */
    @Test
    @Config(sdk = [31])
    fun requestFailureAtTheSummaryLogRestoresModeRouteAndRaisedVolume() {
        installSnapshot()
        val (phone, ops) = flakyPhone()
        ops.failVoiceCallReadNumber = 3

        try {
            phone.request {}
            fail("expected the summary log's volume read to fail")
        } catch (expected: RuntimeException) {
        }

        val audio = audio()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audio.communicationDevice?.type)
        assertEquals("the raised volume must be back at the snapshot", 1, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
    }

    /**
     * abandon reads the voice-call volume before restoring the route; that read throwing
     * used to skip the route and mode restore entirely. Each restore stands on its own.
     */
    @Test
    @Config(sdk = [31])
    fun abandonRestoresRouteAndModeEvenWhenTheVolumeReadFails() {
        installSnapshot()
        val (phone, ops) = flakyPhone()
        assertTrue(phone.request {})
        assertEquals("the session raised the call volume", TARGET_VOLUME, audio().getStreamVolume(AudioManager.STREAM_VOICE_CALL))

        ops.failVolumeReads = true // the audio service starts dying mid-session
        phone.abandon() // must not throw

        val audio = audio()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audio.communicationDevice?.type)
        // The volume could not be read, so it stays raised; that must not take the rest with it.
        assertEquals(TARGET_VOLUME, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
    }

    /**
     * The route restore itself failing (setCommunicationDevice throws) must not skip the
     * volume and mode restores — and clearing is the fallback, not Sierra's speaker.
     */
    @Test
    @Config(sdk = [31])
    fun abandonRestoresVolumeAndModeEvenWhenTheRouteRestoreFails() {
        installSnapshot()
        val (phone, ops) = flakyPhone()
        assertTrue(phone.request {})

        ops.failDeviceSet = true
        phone.abandon() // must not throw

        val audio = audio()
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertEquals("volume back at the snapshot", 1, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
        assertNull("the failed restore falls back to clearing", audio.communicationDevice)
    }

    /**
     * The mode restore failing is reported, not thrown, and the route and volume restores
     * before it have already run.
     */
    @Test
    @Config(sdk = [31])
    fun abandonRestoresRouteAndVolumeEvenWhenTheModeRestoreFails() {
        installSnapshot()
        val (phone, ops) = flakyPhone()
        assertTrue(phone.request {})

        ops.failModeWrites = true
        phone.abandon() // must not throw

        val audio = audio()
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audio.communicationDevice?.type)
        assertEquals("volume back at the snapshot", 1, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
        assertEquals("the mode write failed; it is reported, not thrown", AudioManager.MODE_IN_COMMUNICATION, audio.mode)
    }
}
