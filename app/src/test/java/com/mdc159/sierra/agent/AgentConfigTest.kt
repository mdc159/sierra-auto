package com.mdc159.sierra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigTest {

    private val gateway = AgentConfig(
        baseUrl = "http://gw:4000",
        apiKey = "gw-key",
        model = "groq/openai/gpt-oss-120b",
        sttUrl = "http://gw:4000",
    )

    @Test
    fun gatewayProfileChatsWithTheMainSettings() {
        assertFalse(gateway.usesBridge)
        assertEquals("http://gw:4000/v1/chat/completions", gateway.chatCompletionsUrl())
        assertEquals("gw-key", gateway.chatKey)
        assertEquals("groq/openai/gpt-oss-120b", gateway.chatModel)
        assertTrue(gateway.isConfigured)
    }

    @Test
    fun bridgeProfileMovesChatOnlyAndKeepsVoiceOnTheGateway() {
        val c = gateway.copy(profile = AgentProfile.BRIDGE, bridgeUrl = "https://bridge:8443/v1", bridgeKey = "bt")
        assertEquals("https://bridge:8443/v1/chat/completions", c.chatCompletionsUrl())
        assertEquals("bt", c.chatKey)
        assertEquals(AgentConfig.DEFAULT_BRIDGE_MODEL, c.chatModel)
        // Transcription and gateway speech read these, and they are untouched.
        assertEquals("http://gw:4000", c.baseUrl)
        assertEquals("gw-key", c.apiKey)
        assertEquals("gw-key", c.effectiveSttKey)
        assertEquals("http://gw:4000/v1/audio/transcriptions", c.transcriptionsUrl())
    }

    @Test
    fun bridgeProfileNeedsABridgeUrlButNotTheGateway() {
        assertFalse(gateway.copy(profile = AgentProfile.BRIDGE).isConfigured)
        assertTrue(AgentConfig(profile = AgentProfile.BRIDGE, bridgeUrl = "https://bridge/v1").isConfigured)
        assertFalse(AgentConfig(profile = AgentProfile.BRIDGE, bridgeUrl = "https://bridge/v1", bridgeModel = " ").isConfigured)
    }
}
