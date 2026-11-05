package com.mdc159.sierra.studio54

import com.mdc159.sierra.agent.AgentConfig

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

    /** True when [secrets] would take over the base URL and key. */
    fun isActive(secrets: SecretSource): Boolean =
        !secrets.get(SecretNames.OPENAI_BASE_URL).isNullOrBlank() && !secrets.get(SecretNames.OPENAI_API_KEY).isNullOrBlank()

    /**
     * True when an agent failure is the gateway refusing the key. `AgentClient` reports
     * HTTP failures as "<what> error <code>: …", and keeps the status code private.
     */
    fun isUnauthorized(e: Throwable): Boolean =
        e.message?.let { UNAUTHORIZED.containsMatchIn(it) } == true

    private val UNAUTHORIZED = Regex("\\berror 401\\b")
}
