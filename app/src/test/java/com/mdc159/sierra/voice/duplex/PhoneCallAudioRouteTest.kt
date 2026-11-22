package com.mdc159.sierra.voice.duplex

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * PhoneCallAudio's loudspeaker route (set in [PhoneCallAudio.request], undone in
 * [PhoneCallAudio.abandon], which the controller calls on every ending: stop, failure,
 * focus loss) must restore exactly the route it found, not switch it off wholesale.
 * Before API 31 the route is the speakerphone flag; on 31+ it is the communication
 * device. Turning it off unconditionally took over routing another app had set up
 * (pre-S) or dropped the prior communication device (S+).
 */
@RunWith(RobolectricTestRunner::class)
class PhoneCallAudioRouteTest {

    private fun context() = RuntimeEnvironment.getApplication()
    private fun audio(): AudioManager = context().getSystemService(AudioManager::class.java)

    @Test
    @Config(sdk = [30])
    fun preSKeepsTheSpeakerphoneThatWasAlreadyOn() {
        val audio = audio()
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = true // the owner (a call app, say) was already on the loudspeaker
        val phone = PhoneCallAudio(context())
        assertTrue(phone.request {})

        @Suppress("DEPRECATION")
        assertTrue(audio.isSpeakerphoneOn)
        phone.abandon()

        @Suppress("DEPRECATION")
        assertTrue("speakerphone was on before the session; it must stay on", audio.isSpeakerphoneOn)
    }

    @Test
    @Config(sdk = [30])
    fun preSTurnsItsOwnSpeakerphoneBackOff() {
        val audio = audio()
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = false
        val phone = PhoneCallAudio(context())
        assertTrue(phone.request {})

        @Suppress("DEPRECATION")
        assertTrue(audio.isSpeakerphoneOn)
        phone.abandon()

        @Suppress("DEPRECATION")
        assertFalse("speakerphone was off before the session; the session must switch it back off", audio.isSpeakerphoneOn)
    }

    @Test
    @Config(sdk = [31])
    fun sAndUpRestoresThePriorCommunicationDevice() {
        val audio = audio()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        org.robolectric.Shadows.shadowOf(audio).setAvailableCommunicationDevices(listOf(headset, speaker))
        audio.setCommunicationDevice(headset) // another app's routing, before the session

        val phone = PhoneCallAudio(context())
        assertTrue(phone.request {})
        assertEquals("the session must route to the loudspeaker", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)

        phone.abandon()
        assertEquals(
            "the communication device found before the session was not restored",
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            audio.communicationDevice?.type,
        )
    }

    @Test
    @Config(sdk = [31])
    fun sAndUpClearsTheDeviceWhenThePriorOneDisappearedMidSession() {
        val audio = audio()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        org.robolectric.Shadows.shadowOf(audio).setAvailableCommunicationDevices(listOf(headset, speaker))
        audio.setCommunicationDevice(headset) // another app's routing, before the session

        val phone = PhoneCallAudio(context())
        assertTrue(phone.request({}))
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)

        // The Bluetooth headset disconnects while the session runs: the prior device is gone.
        org.robolectric.Shadows.shadowOf(audio).setAvailableCommunicationDevices(listOf(speaker))
        phone.abandon()

        assertNull(
            "the prior device is gone; restoring it must fall back to clearing, not leave the session's speaker selected",
            audio.communicationDevice,
        )
    }

    @Test
    @Config(sdk = [31])
    fun sAndUpClearsTheDeviceItSetWhenThereWasNoneBefore() {
        val audio = audio()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        org.robolectric.Shadows.shadowOf(audio).setAvailableCommunicationDevices(listOf(speaker))
        assertNull(audio.communicationDevice)

        val phone = PhoneCallAudio(context())
        assertTrue(phone.request {})
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)

        phone.abandon()
        assertNull("no communication device was set before the session; none must be after", audio.communicationDevice)
    }
}
