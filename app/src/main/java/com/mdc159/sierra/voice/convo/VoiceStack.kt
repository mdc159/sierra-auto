package com.mdc159.sierra.voice.convo

import android.content.Context
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.voice.stt.GatewayTranscriber
import com.mdc159.sierra.voice.stt.Transcriber
import com.mdc159.sierra.voice.tts.AndroidAudioFocus
import com.mdc159.sierra.voice.tts.AndroidSpeechOutput
import com.mdc159.sierra.voice.tts.AudioFocusGate
import com.mdc159.sierra.voice.tts.AudioTrackPlayer
import com.mdc159.sierra.voice.tts.ElevenLabsTts
import com.mdc159.sierra.voice.tts.GatewayTts
import com.mdc159.sierra.voice.tts.SpeechOutput
import com.mdc159.sierra.voice.tts.StreamedSpeechOutput

/**
 * Builds the production voice pieces from the saved settings. This is the one place the
 * Talk screen (phase 2) and the settings screen's "Test voice" button need to call.
 */
object VoiceStack {

    /** The voice the settings ask for, or the Android voice when that one is not usable. */
    fun speechOutput(context: Context, settings: VoiceSettings, agent: AgentConfig, focus: AudioFocusGate = AndroidAudioFocus(context)): SpeechOutput {
        val usable = settings.providerIsUsable(gatewayConfigured = agent.baseUrl.isNotBlank())
        return when (if (usable) settings.provider else VoiceProvider.ANDROID) {
            VoiceProvider.ANDROID -> AndroidSpeechOutput(context, focus)
            VoiceProvider.GATEWAY -> StreamedSpeechOutput(
                GatewayTts(agent.baseUrl, agent.apiKey, settings.gatewayTtsModel, settings.gatewayVoice),
                focus,
                { AudioTrackPlayer() },
            )
            VoiceProvider.ELEVENLABS -> StreamedSpeechOutput(
                ElevenLabsTts(settings.elevenLabsKey, settings.elevenLabsVoiceId, settings.elevenLabsModel),
                focus,
                { AudioTrackPlayer() },
            )
        }
    }

    /**
     * Transcription on the gateway with the model from voice settings. Uses the dedicated
     * transcription URL and key from the agent settings when they are set, otherwise the
     * agent URL and key. Returns null when there is no URL at all (phone recognizer case).
     */
    fun transcriber(settings: VoiceSettings, agent: AgentConfig): Transcriber? {
        val url = agent.sttUrl.ifBlank { agent.baseUrl }
        if (url.isBlank()) return null
        val model = settings.sttModel.ifBlank { agent.sttModel }
        return GatewayTranscriber(url, agent.effectiveSttKey, model)
    }
}
