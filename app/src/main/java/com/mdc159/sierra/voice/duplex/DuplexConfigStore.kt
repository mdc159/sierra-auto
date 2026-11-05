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

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val app = context.applicationContext
            val created = try {
                open(app)
            } catch (e: Exception) {
                app.deleteSharedPreferences(FILE)
                open(app)
            }
            prefs = created
            return created
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
        val p = prefs(context)
        val defaults = DuplexConfig()
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
        prefs(context).edit()
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
