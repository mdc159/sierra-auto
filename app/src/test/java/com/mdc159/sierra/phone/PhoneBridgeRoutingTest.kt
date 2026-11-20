package com.mdc159.sierra.phone

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentProfile
import com.mdc159.sierra.agent.ChatMessage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The phone screen's default chat wiring: what [PhoneDeps.chat] builds. This is the path
 * every spoken and typed turn takes, so it must reach the selected endpoint — the Sierra
 * bridge (Hermes on Victoria) when that profile is chosen — and carry the stable
 * X-Sierra-Conversation identity only for bridge chat.
 */
class PhoneBridgeRoutingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun okReply(): MockResponse = MockResponse()
        .setBody(JSONObject().put("choices", listOf(mapOf("message" to mapOf("role" to "assistant", "content" to "Ready.")))).toString())
        .setHeader("Content-Type", "application/json")

    private fun bridgeConfig() = AgentConfig(
        profile = AgentProfile.BRIDGE,
        bridgeUrl = server.url("/v1").toString(),
        bridgeKey = "bridge-token",
        bridgeModel = "hermes",
    )

    @Test
    fun bridgeProfileSendsChatToTheBridgeWithModelTokenAndConversationIdentity() = runBlocking {
        server.enqueue(okReply())
        val config = bridgeConfig()
        val conversationId = "11111111-2222-3333-4444-555555555555"

        val reply = PhoneDeps().chat(config, conversationId, listOf(ChatMessage("user", "hello")))

        assertEquals("Ready.", reply)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer bridge-token", recorded.getHeader("Authorization"))
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("hermes", body.getString("model"))
        assertEquals(conversationId, recorded.getHeader("X-Sierra-Conversation"))
    }

    @Test
    fun gatewayProfileUsesTheMainSettingsAndSendsNoConversationIdentity() = runBlocking {
        server.enqueue(okReply())
        val config = AgentConfig(
            profile = AgentProfile.GATEWAY,
            baseUrl = server.url("/v1").toString(),
            apiKey = "gateway-key",
            model = "chat-model",
        )

        val reply = runBlocking { PhoneDeps().chat(config, "should-not-be-sent", listOf(ChatMessage("user", "hello"))) }

        assertEquals("Ready.", reply)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer gateway-key", recorded.getHeader("Authorization"))
        assertEquals("chat-model", JSONObject(recorded.body.readUtf8()).getString("model"))
        assertNull("the identity header is for bridge sessions only", recorded.getHeader("X-Sierra-Conversation"))
    }
}
