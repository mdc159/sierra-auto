package com.mdc159.sierra.agent

import com.mdc159.sierra.studio54.AgentOverlay
import com.mdc159.sierra.studio54.SecretNames
import com.mdc159.sierra.studio54.SecretSource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HermesRoutingTest {
    private fun secrets(values: Map<String, String>) = object : SecretSource {
        override fun get(name: String) = values[name]
        override fun names() = values.keys
        override val lastFetched = 1L
    }

    @Test fun chatUsesHermesWhileTranscriptionKeepsGatewayKeyAndModelStaysSaved() {
        val hermes = MockWebServer()
        val gateway = MockWebServer()
        hermes.start(); gateway.start()
        try {
            hermes.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Hermes ready"}}]}"""))
            gateway.enqueue(MockResponse().setBody("""{"text":"test words"}"""))
            val typed = AgentConfig(model = "my-existing-model", sttUrl = gateway.url("/v1").toString(), useHermes = true)
            val merged = AgentOverlay.apply(secrets(mapOf(
                SecretNames.OPENAI_BASE_URL to gateway.url("/v1").toString(),
                SecretNames.OPENAI_API_KEY to "gateway-test-key",
                SecretNames.HERMES_BASE_URL to hermes.url("/v1").toString(),
                SecretNames.HERMES_API_KEY to "hermes-test-key",
            )), typed)
            assertTrue(merged.isConfigured)
            assertEquals("my-existing-model", merged.model)
            assertEquals(gateway.url("/v1").toString(), merged.baseUrl) // TTS also keeps the gateway.
            val client = AgentClient(merged)
            assertEquals("Hermes ready", client.chat(listOf(ChatMessage("user", "test"))))
            assertEquals("test words", client.transcribe(byteArrayOf(0, 1)))
            val agentRequest = hermes.takeRequest()
            assertEquals("Bearer hermes-test-key", agentRequest.getHeader("Authorization"))
            assertEquals("hermes", JSONObject(agentRequest.body.readUtf8()).getString("model"))
            val speechRequest = gateway.takeRequest()
            assertEquals("/v1/audio/transcriptions", speechRequest.path)
            assertEquals("Bearer gateway-test-key", speechRequest.getHeader("Authorization"))
            assertEquals("my-existing-model", merged.copy(useHermes = false).chatModel)
            assertEquals(merged.baseUrl, merged.copy(useHermes = false).chatBaseUrl)
        } finally { hermes.shutdown(); gateway.shutdown() }
    }

    @Test fun missingHermesCredentialsNeverFallBackToPlainChat() {
        val config = AgentOverlay.apply(secrets(mapOf(
            SecretNames.OPENAI_BASE_URL to "https://gateway.example/v1",
            SecretNames.OPENAI_API_KEY to "gateway-key",
        )), AgentConfig(model = "saved-model", useHermes = true))
        assertFalse(config.isConfigured)
        assertThrows(AgentException::class.java) { AgentClient(config).chat(listOf(ChatMessage("user", "test"))) }
    }
}
