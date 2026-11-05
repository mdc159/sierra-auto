package com.mdc159.sierra.studio54

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentException
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
    }
}
