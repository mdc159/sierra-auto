package com.mdc159.sierra.voice.convo

import com.mdc159.sierra.voice.stt.GatewayTranscriber
import com.mdc159.sierra.voice.tts.ElevenLabsTts
import com.mdc159.sierra.voice.tts.GatewayTts

/** Which voice reads replies aloud. */
enum class VoiceProvider(val label: String) {
    /** Android's built-in TextToSpeech. Works offline; no key. */
    ANDROID("Android voice (built in)"),

    /** The owner's gateway, `/v1/audio/speech`, streamed. Uses the agent URL and key. */
    GATEWAY("Gateway voice (OpenAI-compatible)"),

    /** ElevenLabs streaming. Needs the ElevenLabs key entered on the phone. */
    ELEVENLABS("ElevenLabs"),
}

/**
 * Voice settings chosen on the phone. Stored encrypted by VoiceSettingsStore, the same way
 * ConfigStore keeps the agent key. The gateway URL and key come from the agent settings.
 */
data class VoiceSettings(
    val provider: VoiceProvider = VoiceProvider.ANDROID,
    val gatewayTtsModel: String = GatewayTts.DEFAULT_MODEL,
    val gatewayVoice: String = GatewayTts.DEFAULT_VOICE,
    val elevenLabsKey: String = "",
    val elevenLabsVoiceId: String = "",
    val elevenLabsModel: String = ElevenLabsTts.DEFAULT_MODEL,
    /** Transcription model on the gateway. Blank keeps whatever the agent settings say. */
    val sttModel: String = GatewayTranscriber.DEFAULT_MODEL,
    /** Keep listening after each reply (no tap between turns). */
    val conversationMode: Boolean = true,
    /** Seconds of listening without speech before the conversation ends itself. */
    val silenceTimeoutSec: Int = 8,
    /** Experimental: speech-to-speech over the OpenAI Realtime protocol via the gateway. Off by default. */
    val realtimeEnabled: Boolean = false,
    val realtimeModel: String = "openai/gpt-realtime-2.1",
    val realtimeVoice: String = "marin",
) {
    /** Whether the chosen provider has what it needs; otherwise the Android voice is used. */
    fun providerIsUsable(gatewayConfigured: Boolean): Boolean = when (provider) {
        VoiceProvider.ANDROID -> true
        VoiceProvider.GATEWAY -> gatewayConfigured && gatewayTtsModel.isNotBlank()
        VoiceProvider.ELEVENLABS -> elevenLabsKey.isNotBlank() && elevenLabsVoiceId.isNotBlank()
    }

    fun conversationConfig(): ConversationConfig = ConversationConfig(
        continuous = conversationMode,
        silenceTimeoutMs = silenceTimeoutSec.coerceIn(2, 60) * 1_000L,
    )

    companion object {
        const val SAMPLE_TEXT = "Hi, I'm Sierra. This is how I'll sound on the road. Ask me anything."
    }
}
