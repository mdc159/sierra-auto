package com.mdc159.sierra.studio54

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.voice.duplex.DuplexConfig

/**
 * Phase 2 hook, pure so it can be unit-tested: when the vault has handed over the gateway
 * address and this agent's gateway key, they replace what is typed in the phone settings.
 * The owner still chooses the model (and the transcription settings) on the phone.
 */
object AgentOverlay {
    /** [typed] with base URL and key from [secrets] when both are known; otherwise [typed] unchanged. */
    fun apply(secrets: SecretSource, typed: AgentConfig): AgentConfig {
        val baseUrl = secrets.get(SecretNames.OPENAI_BASE_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: return typed
        val apiKey = secrets.get(SecretNames.OPENAI_API_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return typed
        return typed.copy(baseUrl = baseUrl, apiKey = apiKey)
    }

    /**
     * The same for full-duplex voice: the OpenAI Realtime base URL and key come from the vault.
     * The built-in model name ([DuplexConfig.DEFAULT_OPENAI_MODEL], OpenAI's own) does not exist
     * on the gateway, so while it is unchanged the gateway's realtime model is used instead.
     * ElevenLabs settings are left alone.
     */
    fun applyDuplex(secrets: SecretSource, typed: DuplexConfig): DuplexConfig {
        val baseUrl = secrets.get(SecretNames.OPENAI_BASE_URL)?.trim()?.takeIf { it.isNotEmpty() } ?: return typed
        val apiKey = secrets.get(SecretNames.OPENAI_API_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return typed
        val model = typed.openAiModel.trim()
            .takeUnless { it.isEmpty() || it == DuplexConfig.DEFAULT_OPENAI_MODEL } ?: GATEWAY_REALTIME_MODEL
        return typed.copy(openAiBaseUrl = baseUrl, openAiApiKey = apiKey, openAiModel = model)
    }

    /**
     * The gateway's realtime model used when the owner has not chosen one. Measured as the
     * fastest gateway realtime model in #39 (docs/VOICE-GATEWAY-CHECK.md).
     */
    const val GATEWAY_REALTIME_MODEL = "openai/gpt-realtime-2.1-mini"

    /** True when [secrets] would take over the base URL and key. */
    fun isActive(secrets: SecretSource): Boolean =
        !secrets.get(SecretNames.OPENAI_BASE_URL).isNullOrBlank() && !secrets.get(SecretNames.OPENAI_API_KEY).isNullOrBlank()

    /**
     * True when an agent failure is the gateway refusing the key. `AgentClient` reports
     * HTTP failures as "<what> error <code>: …", and keeps the status code private; a refused
     * realtime websocket handshake reads "… refused the connection (HTTP <code>).".
     */
    fun isUnauthorized(e: Throwable): Boolean =
        e.message?.let { UNAUTHORIZED.containsMatchIn(it) } == true

    private val UNAUTHORIZED = Regex("\\b(?:error|HTTP) 401\\b")
}
