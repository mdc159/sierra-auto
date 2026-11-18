package com.mdc159.sierra.studio54

import com.mdc159.sierra.voice.convo.SttProvider
import com.mdc159.sierra.voice.convo.VoiceProvider
import com.mdc159.sierra.voice.convo.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOverlayTest {

    private class FakeSecrets(private val values: Map<String, String>) : SecretSource {
        override fun get(name: String): String? = values[name]
        override fun names(): Set<String> = values.keys.toSortedSet()
        override val lastFetched: Long? = if (values.isEmpty()) null else 1L
    }

    private fun vault(key: String?): FakeSecrets =
        if (key == null) FakeSecrets(emptyMap()) else FakeSecrets(mapOf(SecretNames.ELEVENLABS_API_KEY to key))

    /** What the owner chooses on the phone: provider, voice, model — and a typed key as fallback. */
    private val typed = VoiceSettings(
        provider = VoiceProvider.ELEVENLABS,
        elevenLabsKey = "typed-key",
        elevenLabsVoiceId = "jXJ93ZaMvKJrPorQEQlr",
        elevenLabsModel = "eleven_v3",
        sttProvider = SttProvider.ELEVENLABS,
        conversationMode = false,
        silenceTimeoutSec = 12,
    )

    @Test
    fun vaultKeyTakesPrecedenceOverTheTypedKey() {
        val merged = VoiceOverlay.apply(vault(" sk_vault_1 "), typed)
        assertEquals("sk_vault_1", merged.elevenLabsKey) // trimmed
        assertTrue(VoiceOverlay.isActive(vault("sk_vault_1")))
    }

    @Test
    fun everyPreferenceExceptTheKeyStaysTheOwners() {
        val merged = VoiceOverlay.apply(vault("sk_vault_1"), typed)
        assertEquals(VoiceProvider.ELEVENLABS, merged.provider)
        assertEquals("jXJ93ZaMvKJrPorQEQlr", merged.elevenLabsVoiceId)
        assertEquals("eleven_v3", merged.elevenLabsModel)
        assertEquals(SttProvider.ELEVENLABS, merged.sttProvider)
        assertEquals(typed.gatewayTtsModel, merged.gatewayTtsModel)
        assertEquals(typed.sttModel, merged.sttModel)
        assertEquals(typed.elevenLabsSttModel, merged.elevenLabsSttModel)
        assertEquals(false, merged.conversationMode)
        assertEquals(12, merged.silenceTimeoutSec)
        assertEquals(typed.realtimeEnabled, merged.realtimeEnabled)
        assertEquals(typed.realtimeModel, merged.realtimeModel)
        assertEquals(typed.realtimeVoice, merged.realtimeVoice)
    }

    @Test
    fun rotationChangesTheResolvedSettingsSoCachesKeyedOnThemRebuild() {
        val first = VoiceOverlay.apply(vault("sk_vault_1"), typed)
        val second = VoiceOverlay.apply(vault("sk_vault_2"), first)
        assertEquals("sk_vault_2", second.elevenLabsKey)
        assertNotSame(first, second)
        assertFalse(first == second)
        // An unchanged key resolves to the same settings, so nothing rebuilds needlessly.
        assertSame(second, VoiceOverlay.apply(vault("sk_vault_2"), second))
    }

    @Test
    fun blankOrAbsentVaultKeyFallsBackToTheTypedSettings() {
        assertSame(typed, VoiceOverlay.apply(vault(null), typed))
        assertSame(typed, VoiceOverlay.apply(vault(" "), typed))
        assertSame(typed, VoiceOverlay.apply(vault(""), typed))
        // A vault holding only other secrets changes nothing.
        assertSame(typed, VoiceOverlay.apply(FakeSecrets(mapOf(SecretNames.OPENAI_API_KEY to "sk", SecretNames.SIERRA_BRIDGE_TOKEN to "t")), typed))
        assertFalse(VoiceOverlay.isActive(vault(null)))
        assertFalse(VoiceOverlay.isActive(vault(" ")))
    }

    @Test
    fun theTypedSettingsAreImmutableAndNeverCarryTheVaultKeyAfterwards() {
        val merged = VoiceOverlay.apply(vault("sk_vault_1"), typed)
        assertNotSame(typed, merged)
        assertEquals("typed-key", typed.elevenLabsKey) // the owner's copy is untouched
        assertEquals("sk_vault_1", merged.elevenLabsKey)
    }

    @Test
    fun aVaultKeyMakesABlankTypedKeyUsableForVoiceAndTranscription() {
        // The parent flow: provider ELEVENLABS and the voice chosen in the UI, key field left blank.
        val merged = VoiceOverlay.apply(vault("sk_vault_1"), typed.copy(elevenLabsKey = ""))
        assertEquals("sk_vault_1", merged.elevenLabsKey)
        assertTrue(merged.providerIsUsable(gatewayConfigured = false))
        assertTrue(merged.sttProviderIsUsable())
        // Without the vault, the same typed settings stay unusable (fallback behaviour).
        assertFalse(VoiceOverlay.apply(vault(null), typed.copy(elevenLabsKey = "")).providerIsUsable(gatewayConfigured = false))
    }
}
