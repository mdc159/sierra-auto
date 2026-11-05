package com.mdc159.sierra.voice.duplex

/** Which realtime voice service runs the conversation. */
enum class DuplexProvider {
    /** OpenAI Realtime protocol over websocket, at OpenAI or any compatible gateway. */
    OPENAI_REALTIME,

    /** ElevenLabs Agents (conversational) over websocket. */
    ELEVENLABS,
}

/** How the microphone is handled while the assistant is speaking. See docs/DUPLEX.md. */
enum class DuplexMode {
    /** Both sides talk at once. The service's own turn detection decides who has the floor. */
    FULL_DUPLEX,

    /**
     * Mic frames are replaced with silence while the assistant speaks, unless they are
     * clearly louder than the playback. A tap is the fallback interrupt.
     */
    GUARDED,
}

/**
 * Settings for one duplex session. Stored encrypted by [DuplexConfigStore], edited in
 * DuplexSettingsActivity.
 */
data class DuplexConfig(
    val provider: DuplexProvider = DuplexProvider.OPENAI_REALTIME,
    val mode: DuplexMode = DuplexMode.GUARDED,

    /** OpenAI-compatible base URL, with or without `/v1`. The gateway or OpenAI itself. */
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val openAiApiKey: String = "",
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val openAiVoice: String = DEFAULT_OPENAI_VOICE,
    /** Model for input transcription inside the realtime session. Blank disables it. */
    val openAiTranscriptionModel: String = DEFAULT_OPENAI_TRANSCRIPTION_MODEL,

    val elevenApiKey: String = "",
    val elevenAgentId: String = "",
    /** Optional voice override. Blank keeps the voice chosen in the ElevenLabs dashboard. */
    val elevenVoiceId: String = "",
    /** Optional base URL, for tests or a regional endpoint. */
    val elevenBaseUrl: String = DEFAULT_ELEVEN_BASE_URL,

    val instructions: String = DEFAULT_INSTRUCTIONS,

    /** Guarded mode: mic must be this many times louder (RMS) than playback to pass. */
    val guardRatio: Double = 2.0,
) {
    val isConfigured: Boolean
        get() = when (provider) {
            DuplexProvider.OPENAI_REALTIME -> openAiBaseUrl.isNotBlank() && openAiModel.isNotBlank()
            DuplexProvider.ELEVENLABS -> elevenAgentId.isNotBlank()
        }

    companion object {
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-realtime"
        const val DEFAULT_OPENAI_VOICE = "marin"
        const val DEFAULT_OPENAI_TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"
        const val DEFAULT_ELEVEN_BASE_URL = "https://api.elevenlabs.io"

        const val DEFAULT_INSTRUCTIONS =
            "You are Sierra, a voice assistant on the dashboard of the owner's GMC Sierra truck. " +
                "The driver is driving. Speak plain English in one to three short sentences. " +
                "No lists, no URLs, no emoji. If a question needs the owner's own agent, " +
                "memory or tools, call ask_agent and relay its answer in your own words."
    }
}
