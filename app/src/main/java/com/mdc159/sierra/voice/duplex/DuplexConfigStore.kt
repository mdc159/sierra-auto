package com.mdc159.sierra.voice.duplex

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Duplex settings, encrypted at rest with a key in the Android Keystore. Same scheme as
 * agent/ConfigStore, in its own file so the two never clash.
 */
object DuplexConfigStore {
    private const val FILE = "sierra_duplex_config"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Why the encrypted store could not be opened in this run of the app, or null. While set,
     * [load] and [save] work on a copy in memory, starting from the defaults: the file on disk
     * is left exactly as it is (it is never deleted), and the next start of the app tries again.
     */
    @Volatile
    var openError: String? = null
        private set

    /** The settings for this run when the store is unavailable. */
    @Volatile
    private var inMemory: DuplexConfig? = null

    /** The encrypted store, or null when it cannot be opened in this run (see [openError]). */
    private fun prefs(context: Context): SharedPreferences? {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            if (openError != null) return null
            return try {
                open(context.applicationContext).also { prefs = it }
            } catch (e: Exception) {
                // Keystore and file errors carry no setting values; the saved file is kept.
                val why = "${e.javaClass.simpleName}: ${DuplexLog.redact(e.message, 120)}"
                openError = why
                DuplexLog.w("duplex settings store could not be opened; using defaults in memory for this run, file kept ($why)")
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun open(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(context: Context): DuplexConfig {
        val defaults = DuplexConfig()
        val p = prefs(context) ?: return inMemory ?: defaults
        return DuplexConfig(
            provider = enumOr(p.getString("provider", null), defaults.provider),
            mode = enumOr(p.getString("mode", null), defaults.mode),
            openAiBaseUrl = p.getString("openai_base_url", "").orEmpty().ifBlank { defaults.openAiBaseUrl },
            openAiApiKey = p.getString("openai_api_key", "").orEmpty(),
            openAiModel = p.getString("openai_model", "").orEmpty().ifBlank { defaults.openAiModel },
            openAiVoice = p.getString("openai_voice", "").orEmpty().ifBlank { defaults.openAiVoice },
            openAiTranscriptionModel = p.getString("openai_stt_model", defaults.openAiTranscriptionModel).orEmpty(),
            elevenApiKey = p.getString("eleven_api_key", "").orEmpty(),
            elevenAgentId = p.getString("eleven_agent_id", "").orEmpty(),
            elevenVoiceId = p.getString("eleven_voice_id", "").orEmpty(),
            instructions = p.getString("instructions", "").orEmpty().ifBlank { defaults.instructions },
            guardRatio = p.getString("guard_ratio", null)?.toDoubleOrNull() ?: defaults.guardRatio,
        )
    }

    fun save(context: Context, c: DuplexConfig) {
        val p = prefs(context)
        if (p == null) {
            inMemory = c
            return
        }
        p.edit()
            .putString("provider", c.provider.name)
            .putString("mode", c.mode.name)
            .putString("openai_base_url", c.openAiBaseUrl.trim())
            .putString("openai_api_key", c.openAiApiKey.trim())
            .putString("openai_model", c.openAiModel.trim())
            .putString("openai_voice", c.openAiVoice.trim())
            .putString("openai_stt_model", c.openAiTranscriptionModel.trim())
            .putString("eleven_api_key", c.elevenApiKey.trim())
            .putString("eleven_agent_id", c.elevenAgentId.trim())
            .putString("eleven_voice_id", c.elevenVoiceId.trim())
            .putString("instructions", c.instructions.trim())
            .putString("guard_ratio", c.guardRatio.toString())
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
