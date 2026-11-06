package com.mdc159.sierra.studio54

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.voice.duplex.DuplexConfig
import com.mdc159.sierra.voice.duplex.DuplexException
import com.mdc159.sierra.voice.duplex.DuplexProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOverlayTest {

    private class FakeSecrets(private val values: Map<String, String>) : SecretSource {
        override fun get(name: String): String? = values[name]
        override fun names(): Set<String> = values.keys.toSortedSet()
        override val lastFetched: Long? = if (values.isEmpty()) null else 1L
    }

    private val typed = AgentConfig(
        baseUrl = "https://typed.example/v1",
        apiKey = "typed-key",
        model = "gpt-4o-mini",
        sttUrl = "https://stt.example/v1",
        sttModel = "whisper-1",
    )

    @Test
    fun vaultValuesReplaceBaseUrlAndKeyOnly() {
        val secrets = FakeSecrets(
            mapOf(
                SecretNames.OPENAI_BASE_URL to " http://gw.tail1234.ts.net:4000 ",
                SecretNames.OPENAI_API_KEY to "sk-vault",
                SecretNames.ELEVENLABS_API_KEY to "el",
            ),
        )
        val merged = AgentOverlay.apply(secrets, typed)
        assertEquals("http://gw.tail1234.ts.net:4000", merged.baseUrl)
        assertEquals("sk-vault", merged.apiKey)
        assertEquals("gpt-4o-mini", merged.model) // the owner still chooses the model
        assertEquals("https://stt.example/v1", merged.sttUrl)
        assertEquals("sk-vault", merged.effectiveSttKey) // blank transcription key falls back to the vault key
        assertEquals("http://gw.tail1234.ts.net:4000/v1/chat/completions", merged.chatCompletionsUrl())
        assertTrue(AgentOverlay.isActive(secrets))
    }

    @Test
    fun typedSettingsAreKeptWhenTheVaultHasNothingOrOnlyHalf() {
        assertSame(typed, AgentOverlay.apply(FakeSecrets(emptyMap()), typed))
        assertSame(typed, AgentOverlay.apply(FakeSecrets(mapOf(SecretNames.OPENAI_BASE_URL to "http://gw")), typed))
        assertSame(typed, AgentOverlay.apply(FakeSecrets(mapOf(SecretNames.OPENAI_API_KEY to "sk")), typed))
        assertSame(
            typed,
            AgentOverlay.apply(FakeSecrets(mapOf(SecretNames.OPENAI_BASE_URL to " ", SecretNames.OPENAI_API_KEY to "sk")), typed),
        )
        assertFalse(AgentOverlay.isActive(FakeSecrets(emptyMap())))
    }

    @Test
    fun aVaultKeyMakesAnUnconfiguredPhoneUsableOnceTheModelIsChosen() {
        val secrets = FakeSecrets(mapOf(SecretNames.OPENAI_BASE_URL to "http://gw", SecretNames.OPENAI_API_KEY to "sk"))
        assertFalse(AgentOverlay.apply(secrets, AgentConfig()).isConfigured)
        assertTrue(AgentOverlay.apply(secrets, AgentConfig(model = "m")).isConfigured)
    }

    @Test
    fun onlyAGateway401CountsAsUnauthorized() {
        assertTrue(AgentOverlay.isUnauthorized(AgentException("Agent error 401: Incorrect API key provided")))
        assertTrue(AgentOverlay.isUnauthorized(AgentException("Transcription error 401")))
        assertFalse(AgentOverlay.isUnauthorized(AgentException("Agent error 4010: odd")))
        assertFalse(AgentOverlay.isUnauthorized(AgentException("Agent error 403: forbidden")))
        assertFalse(AgentOverlay.isUnauthorized(AgentException("Timed out. Check signal and try again.")))
        assertFalse(AgentOverlay.isUnauthorized(RuntimeException()))
        assertTrue(AgentOverlay.isUnauthorized(DuplexException("The voice service refused the connection (HTTP 401).")))
        assertFalse(AgentOverlay.isUnauthorized(DuplexException("The voice service refused the connection (HTTP 403).")))
    }

    private val gateway = FakeSecrets(
        mapOf(SecretNames.OPENAI_BASE_URL to " http://gw.tail1234.ts.net:4000 ", SecretNames.OPENAI_API_KEY to "sk-vault"),
    )

    @Test
    fun duplexTakesTheGatewayAndSwapsOnlyTheBuiltInModelName() {
        // Untouched settings: OpenAI's address, no key, OpenAI's own model name.
        val merged = AgentOverlay.applyDuplex(gateway, DuplexConfig())
        assertEquals("http://gw.tail1234.ts.net:4000", merged.openAiBaseUrl)
        assertEquals("sk-vault", merged.openAiApiKey)
        assertEquals(AgentOverlay.GATEWAY_REALTIME_MODEL, merged.openAiModel)
        assertTrue(merged.isConfigured)
        // In-session transcription keeps OpenAI's bare name: the gateway passes it upstream and
        // rejects the prefixed gateway name (checked against the gateway on 2026-11-05).
        assertEquals(DuplexConfig.DEFAULT_OPENAI_TRANSCRIPTION_MODEL, merged.openAiTranscriptionModel)

        val chosen = AgentOverlay.applyDuplex(gateway, DuplexConfig(openAiModel = "openai/gpt-realtime-2.1"))
        assertEquals("openai/gpt-realtime-2.1", chosen.openAiModel)
        assertEquals(AgentOverlay.GATEWAY_REALTIME_MODEL, AgentOverlay.applyDuplex(gateway, DuplexConfig(openAiModel = " ")).openAiModel)
    }

    @Test
    fun duplexLeavesElevenLabsAndTypedSettingsAloneWithoutTheVault() {
        val typedDuplex = DuplexConfig(
            provider = DuplexProvider.ELEVENLABS,
            openAiBaseUrl = "https://typed.example/v1",
            openAiApiKey = "typed",
            elevenApiKey = "el-typed",
            elevenAgentId = "agent_1",
        )
        assertSame(typedDuplex, AgentOverlay.applyDuplex(FakeSecrets(emptyMap()), typedDuplex))
        assertSame(typedDuplex, AgentOverlay.applyDuplex(FakeSecrets(mapOf(SecretNames.OPENAI_BASE_URL to "http://gw")), typedDuplex))
        val merged = AgentOverlay.applyDuplex(gateway, typedDuplex)
        assertEquals("el-typed", merged.elevenApiKey)
        assertEquals("agent_1", merged.elevenAgentId)
        assertEquals(DuplexProvider.ELEVENLABS, merged.provider)
    }
}
