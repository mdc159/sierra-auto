package com.mdc159.sierra.studio54

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.voice.duplex.DuplexConfig

/**
 * Phase 2 hook, pure so it can be unit-tested: when the vault has handed over the gateway
 * address and this agent's gateway key, they replace what is typed in the phone settings.
 * The owner still chooses the model (and the transcription settings) on the phone.
 */
object AgentOverlay {
    /**
     * [typed] with the gateway base URL and key from [secrets] when both are known, and the
     * bridge URL and token when both of those are known; each pair independently. Nothing
     * known: [typed] unchanged. Profile and model choices stay the owner's.
     */
    fun apply(secrets: SecretSource, typed: AgentConfig): AgentConfig {
        var merged = typed
        pair(secrets, SecretNames.OPENAI_BASE_URL, SecretNames.OPENAI_API_KEY)?.let { (url, key) ->
            merged = merged.copy(baseUrl = url, apiKey = key)
        }
        pair(secrets, SecretNames.SIERRA_BRIDGE_URL, SecretNames.SIERRA_BRIDGE_TOKEN)?.let { (url, key) ->
            merged = merged.copy(bridgeUrl = url, bridgeKey = key)
        }
        return merged
    }

    /** True when [secrets] would take over the bridge URL and token. */
    fun isBridgeActive(secrets: SecretSource): Boolean =
        pair(secrets, SecretNames.SIERRA_BRIDGE_URL, SecretNames.SIERRA_BRIDGE_TOKEN) != null

    private fun pair(secrets: SecretSource, urlName: String, keyName: String): Pair<String, String>? {
        val url = secrets.get(urlName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = secrets.get(keyName)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return url to key
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

    private val UNAUTHORIZED = Regex("\\berror 401\\b|refused the connection \\(HTTP 401\\)")
}
