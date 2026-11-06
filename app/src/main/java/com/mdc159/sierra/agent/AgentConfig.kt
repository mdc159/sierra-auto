package com.mdc159.sierra.agent

/** Which endpoint answers Talk and `ask_agent`. Transcription always uses the main settings. */
enum class AgentProfile {
    /** The base URL, key and model in the main settings: the Studio 54 gateway, or any provider. */
    GATEWAY,

    /** The owner's own agents behind the Sierra bridge (`bridge/`), chosen by [AgentConfig.bridgeModel]. */
    BRIDGE,
}

/**
 * Where the agent lives. [baseUrl] is any OpenAI-compatible base URL, with or without
 * a trailing `/v1`. The transcription endpoint is optional; when [sttUrl] is blank,
 * speech is recognised on the phone instead.
 */
data class AgentConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val sttUrl: String = "",
    val sttKey: String = "",
    val sttModel: String = DEFAULT_STT_MODEL,
    val profile: AgentProfile = AgentProfile.GATEWAY,
    /** The bridge's base URL, its `SIERRA_BRIDGE_TOKEN`, and the `[models.<name>]` entry to talk to. */
    val bridgeUrl: String = "",
    val bridgeKey: String = "",
    val bridgeModel: String = DEFAULT_BRIDGE_MODEL,
) {
    val usesBridge: Boolean get() = profile == AgentProfile.BRIDGE
    val targetLabel: String get() = if (usesBridge) "Sierra bridge agent" else "Configured chat model"

    /*
     * Where chat goes: the bridge when chosen, otherwise the main settings. Only chat uses
     * these. Transcription, gateway speech and full-duplex voice keep [baseUrl] and [apiKey],
     * so the bridge token never reaches them and the voice never goes to the bridge.
     */
    val chatBaseUrl: String get() = if (usesBridge) bridgeUrl else baseUrl
    val chatKey: String get() = if (usesBridge) bridgeKey else apiKey
    val chatModel: String get() = if (usesBridge) bridgeModel else model

    /**
     * True when chat has somewhere to go: Talk and `ask_agent` can run. The bridge always
     * checks a token; a gateway-profile provider may be keyless (a local Ollama).
     */
    val isConfigured: Boolean
        get() = chatBaseUrl.isNotBlank() && chatModel.isNotBlank() && (!usesBridge || bridgeKey.isNotBlank())
    val hasTranscription: Boolean get() = sttUrl.isNotBlank()

    /** Transcription falls back to the agent key when no separate key is given. */
    val effectiveSttKey: String get() = sttKey.ifBlank { apiKey }

    fun chatCompletionsUrl(): String = endpoint(chatBaseUrl, "chat/completions")
    fun transcriptionsUrl(): String = endpoint(sttUrl, "audio/transcriptions")
    fun modelsUrl(): String = endpoint(chatBaseUrl, "models")

    companion object {
        const val DEFAULT_STT_MODEL = "whisper-1"
        const val DEFAULT_BRIDGE_MODEL = "hermes"

        /**
         * Accepts `https://host`, `https://host/v1`, or the full endpoint URL and
         * returns the full endpoint URL.
         */
        fun endpoint(base: String, path: String): String {
            val trimmed = base.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/$path") -> trimmed
                Regex(".*/v\\d+[a-z]*$").matches(trimmed) -> "$trimmed/$path"
                else -> "$trimmed/v1/$path"
            }
        }
    }
}
