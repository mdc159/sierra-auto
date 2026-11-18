package com.mdc159.sierra.studio54

import com.mdc159.sierra.voice.convo.VoiceSettings

/**
 * Phase 2 hook, pure so it can be unit-tested: when the vault holds an ElevenLabs key, it
 * replaces the one typed in the voice settings — in memory only, never shown or saved. The
 * owner still chooses the provider, the voice, the models and every other preference.
 */
object VoiceOverlay {
    /**
     * [typed] with [VoiceSettings.elevenLabsKey] taken from [secrets] when the vault has a
     * nonblank [SecretNames.ELEVENLABS_API_KEY]; otherwise [typed] unchanged, so the typed
     * key stays the fallback. Callers must not persist or display the resolved key.
     */
    fun apply(secrets: SecretSource, typed: VoiceSettings): VoiceSettings {
        val key = secrets.get(SecretNames.ELEVENLABS_API_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return typed
        if (key == typed.elevenLabsKey) return typed
        return typed.copy(elevenLabsKey = key)
    }

    /** True when [secrets] would supply the ElevenLabs key, so the typed one is not used. */
    fun isActive(secrets: SecretSource): Boolean =
        !secrets.get(SecretNames.ELEVENLABS_API_KEY).isNullOrBlank()
}
