package com.mdc159.sierra.voice.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class MicrophoneSource(val label: String) {
    AUTOMATIC("Automatic (existing behavior)"),
    CAR("Car microphone (Android Auto)"),
    PHONE("Phone microphone"),
}

object MicSettings {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("microphone_settings", Context.MODE_PRIVATE)
    fun source(context: Context): MicrophoneSource = runCatching {
        MicrophoneSource.valueOf(prefs(context).getString("source", MicrophoneSource.AUTOMATIC.name)!!)
    }.getOrDefault(MicrophoneSource.AUTOMATIC)
    fun source(context: Context, source: MicrophoneSource) { prefs(context).edit().putString("source", source.name).apply() }
    fun manual(context: Context) = prefs(context).getBoolean("manual", false)
    fun manual(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("manual", enabled).apply() }
    fun permitted(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun health(context: Context): String {
        val muted = runCatching { context.getSystemService(AudioManager::class.java).isMicrophoneMute }.getOrDefault(false)
        return "Permission: ${if (permitted(context)) "granted" else "NOT granted"}; system mic mute: $muted. " +
            "If audio is silent, also check the phone's Microphone access switch in Quick Settings."
    }
}
