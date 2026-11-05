package com.mdc159.sierra.voice.convo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsTest {

    @Test
    fun defaultsAreSafe() {
        val s = VoiceSettings()
        assertEquals(VoiceProvider.ANDROID, s.provider)
        assertEquals(SttProvider.GATEWAY, s.sttProvider)
        assertFalse("realtime must be off by default", s.realtimeEnabled)
        assertTrue(s.conversationMode)
        assertEquals("openai/gpt-realtime-2.1-mini", s.realtimeModel)
    }

    @Test
    fun providerUsability() {
        assertTrue(VoiceSettings(provider = VoiceProvider.GATEWAY).providerIsUsable(gatewayConfigured = true))
        assertFalse(VoiceSettings(provider = VoiceProvider.GATEWAY).providerIsUsable(gatewayConfigured = false))
        assertFalse(VoiceSettings(provider = VoiceProvider.ELEVENLABS, elevenLabsKey = "k").providerIsUsable(true))
        assertTrue(VoiceSettings(provider = VoiceProvider.ELEVENLABS, elevenLabsKey = "k", elevenLabsVoiceId = "v").providerIsUsable(false))
        assertFalse(VoiceSettings(sttProvider = SttProvider.ELEVENLABS).sttProviderIsUsable())
        assertTrue(VoiceSettings(sttProvider = SttProvider.ELEVENLABS, elevenLabsKey = "k").sttProviderIsUsable())
    }

    @Test
    fun conversationConfigFollowsSettings() {
        val c = VoiceSettings(conversationMode = false, silenceTimeoutSec = 90).conversationConfig()
        assertFalse(c.continuous)
        assertEquals(60_000L, c.silenceTimeoutMs) // clamped to 60 s
        assertEquals(2_000L, VoiceSettings(silenceTimeoutSec = 0).conversationConfig().silenceTimeoutMs)
    }
}
