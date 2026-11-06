package com.mdc159.sierra.agent

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
    val useHermes: Boolean = false,
    /** Runtime-only vault values; ConfigStore never persists these with the typed settings. */
    val hermesBaseUrl: String = "",
    val hermesApiKey: String = "",
) {
    val chatBaseUrl: String get() = if (useHermes) hermesBaseUrl else baseUrl
    val chatApiKey: String get() = if (useHermes) hermesApiKey else apiKey
    val chatModel: String get() = if (useHermes) "hermes" else model
    val targetLabel: String get() = if (useHermes) "Hermes on Victoria" else "Configured chat model"
    val isConfigured: Boolean get() = chatBaseUrl.isNotBlank() && chatModel.isNotBlank() && (!useHermes || chatApiKey.isNotBlank())
    val hasTranscription: Boolean get() = sttUrl.isNotBlank()

    /** Transcription falls back to the agent key when no separate key is given. */
    val effectiveSttKey: String get() = sttKey.ifBlank { apiKey }

    fun chatCompletionsUrl(): String = endpoint(chatBaseUrl, "chat/completions")
    fun transcriptionsUrl(): String = endpoint(sttUrl, "audio/transcriptions")
    fun modelsUrl(): String = endpoint(baseUrl, "models")

    companion object {
        const val DEFAULT_STT_MODEL = "whisper-1"

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
