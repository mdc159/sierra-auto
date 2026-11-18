package com.mdc159.sierra.phone

import com.mdc159.sierra.voice.convo.ConversationConfig
import com.mdc159.sierra.voice.convo.VoiceSettings

/** The handset produces much quieter PCM than the car detector's default assumes. */
internal fun VoiceSettings.phoneConversationConfig(): ConversationConfig {
    val base = conversationConfig()
    return base.copy(
        endpoint = base.endpoint.copy(
            // A54 evidence: room noise 3–10 RMS, spoken phrases 89–279 RMS.
            // Keep the adaptive noise ratio; lower only the handset's absolute floor.
            minSpeechRms = 40.0,
            // An explicit Talk tap must not silently discard a quiet first question.
            // Digital silence is still rejected, and the controller disables this
            // fallback for unattended follow-ups so quiet ends the conversation.
            sendUnconfirmed = true,
        ),
    )
}
