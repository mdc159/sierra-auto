package com.mdc159.sierra.agent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BridgeConversationIdentityTest {
    @Test fun equalRepliesDoNotMixConversationsAndClearStartsNewIdentity() {
        val first = Conversation()
        val second = Conversation()
        val initial = first.id
        first.record("hello", "Okay.")
        second.record("hello", "Okay.")
        assertEquals(initial, first.id)
        assertNotEquals(first.id, second.id)
        first.clear()
        assertNotEquals(initial, first.id)
        assertEquals(0, first.size())
    }

    @Test fun stableIdentityIsSentOnlyToBridgeChat() {
        val server = MockWebServer()
        server.start()
        try {
            val conversation = Conversation()
            val config = AgentConfig(baseUrl = server.url("/v1").toString(), model = "model",
                profile = AgentProfile.BRIDGE, bridgeUrl = server.url("/v1").toString(), bridgeModel = "hermes", bridgeKey = "test",
                sttUrl = server.url("/v1").toString())
            val client = AgentClient(config, conversationId = conversation.id)
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Okay."}}]}"""))
                client.chat(conversation.messagesFor("hello"))
                assertEquals(conversation.id, server.takeRequest().getHeader("X-Sierra-Conversation"))
            }
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Okay."}}]}"""))
            AgentClient(config.copy(profile = AgentProfile.GATEWAY), conversationId = conversation.id).chat(conversation.messagesFor("hello"))
            assertNull(server.takeRequest().getHeader("X-Sierra-Conversation"))
            server.enqueue(MockResponse().setBody("""{"text":"hello"}"""))
            client.transcribe(ByteArray(44))
            assertNull(server.takeRequest().getHeader("X-Sierra-Conversation"))
        } finally {
            server.shutdown()
        }
    }
}
