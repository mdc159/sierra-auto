package com.mdc159.sierra.voice.convo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Voice settings, encrypted at rest with a key held in the Android Keystore: the same
 * scheme ConfigStore uses for the agent key, in a separate file so the two lanes never
 * write the same preferences.
 */
object VoiceSettingsStore {
    private const val FILE = "sierra_voice_config"

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
                // Unreadable store (for example after a keystore reset): start fresh rather than crash.
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

    fun load(context: Context): VoiceSettings {
        val p = prefs(context)
        val defaults = VoiceSettings()
        return VoiceSettings(
            provider = runCatching { VoiceProvider.valueOf(p.getString("provider", "").orEmpty()) }.getOrDefault(defaults.provider),
            gatewayTtsModel = p.getString("gateway_tts_model", "").orEmpty().ifBlank { defaults.gatewayTtsModel },
            gatewayVoice = p.getString("gateway_voice", "").orEmpty().ifBlank { defaults.gatewayVoice },
            elevenLabsKey = p.getString("elevenlabs_key", "").orEmpty(),
            elevenLabsVoiceId = p.getString("elevenlabs_voice_id", "").orEmpty(),
            elevenLabsModel = p.getString("elevenlabs_model", "").orEmpty().ifBlank { defaults.elevenLabsModel },
            sttModel = p.getString("stt_model", "").orEmpty().ifBlank { defaults.sttModel },
            conversationMode = p.getBoolean("conversation_mode", defaults.conversationMode),
            silenceTimeoutSec = p.getInt("silence_timeout_sec", defaults.silenceTimeoutSec),
            realtimeEnabled = p.getBoolean("realtime_enabled", defaults.realtimeEnabled),
            realtimeModel = p.getString("realtime_model", "").orEmpty().ifBlank { defaults.realtimeModel },
            realtimeVoice = p.getString("realtime_voice", "").orEmpty().ifBlank { defaults.realtimeVoice },
        )
    }

    fun save(context: Context, s: VoiceSettings) {
        prefs(context).edit()
            .putString("provider", s.provider.name)
            .putString("gateway_tts_model", s.gatewayTtsModel.trim())
            .putString("gateway_voice", s.gatewayVoice.trim())
            .putString("elevenlabs_key", s.elevenLabsKey.trim())
            .putString("elevenlabs_voice_id", s.elevenLabsVoiceId.trim())
            .putString("elevenlabs_model", s.elevenLabsModel.trim())
            .putString("stt_model", s.sttModel.trim())
            .putBoolean("conversation_mode", s.conversationMode)
            .putInt("silence_timeout_sec", s.silenceTimeoutSec)
            .putBoolean("realtime_enabled", s.realtimeEnabled)
            .putString("realtime_model", s.realtimeModel.trim())
            .putString("realtime_voice", s.realtimeVoice.trim())
            .apply()
    }
}
