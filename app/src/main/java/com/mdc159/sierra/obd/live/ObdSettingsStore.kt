package com.mdc159.sierra.obd.live

import android.content.Context

/** Plain SharedPreferences: an adapter name and MAC address are not secrets. */
object ObdSettingsStore {
    private const val FILE = "sierra_obd"

    fun load(context: Context): ObdSettings {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val kind = runCatching { AdapterKind.valueOf(p.getString("kind", null) ?: "") }.getOrDefault(AdapterKind.DEMO)
        return ObdSettings(
            kind = kind,
            address = p.getString("address", "").orEmpty(),
            name = p.getString("name", "").orEmpty(),
        )
    }

    fun save(context: Context, settings: ObdSettings) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("kind", settings.kind.name)
            .putString("address", settings.address.trim())
            .putString("name", settings.name.trim())
            .apply()
    }
}
