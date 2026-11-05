package com.mdc159.sierra.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Agent settings, encrypted at rest with a key held in the Android Keystore. */
object ConfigStore {
    private const val FILE = "sierra_agent_config"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Suppress("DEPRECATION")
    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val app = context.applicationContext
            val created = try {
                open(app)
            } catch (e: Exception) {
                // A corrupted or unreadable store (for example after a keystore reset):
                // start fresh rather than crash the car screen.
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

    fun load(context: Context): AgentConfig {
        val p = prefs(context)
        return AgentConfig(
            baseUrl = p.getString("base_url", "").orEmpty(),
            apiKey = p.getString("api_key", "").orEmpty(),
            model = p.getString("model", "").orEmpty(),
            sttUrl = p.getString("stt_url", "").orEmpty(),
            sttKey = p.getString("stt_key", "").orEmpty(),
            sttModel = p.getString("stt_model", "").orEmpty().ifBlank { AgentConfig.DEFAULT_STT_MODEL },
        )
    }

    fun save(context: Context, config: AgentConfig) {
        prefs(context).edit()
            .putString("base_url", config.baseUrl.trim())
            .putString("api_key", config.apiKey.trim())
            .putString("model", config.model.trim())
            .putString("stt_url", config.sttUrl.trim())
            .putString("stt_key", config.sttKey.trim())
            .putString("stt_model", config.sttModel.trim())
            .apply()
    }
}
