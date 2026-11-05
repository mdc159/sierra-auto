package com.mdc159.sierra.studio54

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Persistence for the sign-in and the secret cache. Implemented by encrypted prefs on the phone. */
interface Studio54Store {
    fun loadCredentials(): Studio54Credentials?
    fun saveCredentials(credentials: Studio54Credentials)
    fun loadCache(): SecretCache?
    fun saveCache(cache: SecretCache)

    /** Wipes the sign-in and the cache. */
    fun clear()
}

/**
 * Stores the sign-in and the cache with the same scheme ConfigStore uses for the agent key:
 * EncryptedSharedPreferences under an AES-256-GCM master key in the Android Keystore.
 */
class EncryptedStudio54Store(context: Context) : Studio54Store {
    private val app = context.applicationContext

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val created = try {
                open()
            } catch (e: Exception) {
                // A corrupted or unreadable store (for example after a keystore reset):
                // start fresh; the owner signs in again rather than the app crashing.
                app.deleteSharedPreferences(FILE)
                open()
            }
            prefs = created
            return created
        }
    }

    @Suppress("DEPRECATION")
    private fun open(): SharedPreferences {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun loadCredentials(): Studio54Credentials? {
        val p = prefs()
        val c = Studio54Credentials(
            apiUrl = p.getString(KEY_API_URL, "").orEmpty(),
            clientId = p.getString(KEY_CLIENT_ID, "").orEmpty(),
            clientSecret = p.getString(KEY_CLIENT_SECRET, "").orEmpty(),
            projectId = p.getString(KEY_PROJECT_ID, "").orEmpty(),
            keysProjectId = p.getString(KEY_KEYS_PROJECT_ID, null),
        )
        return c.takeIf { it.isComplete }
    }

    override fun saveCredentials(credentials: Studio54Credentials) {
        prefs().edit()
            .putString(KEY_API_URL, credentials.apiUrl)
            .putString(KEY_CLIENT_ID, credentials.clientId)
            .putString(KEY_CLIENT_SECRET, credentials.clientSecret)
            .putString(KEY_PROJECT_ID, credentials.projectId)
            .putString(KEY_KEYS_PROJECT_ID, credentials.keysProjectId ?: "")
            .apply()
    }

    override fun loadCache(): SecretCache? = SecretCache.fromJson(prefs().getString(KEY_CACHE, null))

    override fun saveCache(cache: SecretCache) {
        prefs().edit().putString(KEY_CACHE, cache.toJson()).apply()
    }

    override fun clear() {
        // commit(), not apply(): "Sign out" must be complete before the screen says it is.
        prefs().edit().clear().commit()
    }

    private companion object {
        const val FILE = "sierra_studio54"
        const val KEY_API_URL = "api_url"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_KEYS_PROJECT_ID = "keys_project_id"
        const val KEY_CACHE = "cache"
    }
}

/** Plain in-memory store for JVM tests. */
class InMemoryStudio54Store : Studio54Store {
    private var credentials: Studio54Credentials? = null
    private var cacheJson: String? = null

    override fun loadCredentials(): Studio54Credentials? = credentials
    override fun saveCredentials(credentials: Studio54Credentials) { this.credentials = credentials }
    override fun loadCache(): SecretCache? = SecretCache.fromJson(cacheJson)
    override fun saveCache(cache: SecretCache) { cacheJson = cache.toJson() }
    override fun clear() { credentials = null; cacheJson = null }
}
