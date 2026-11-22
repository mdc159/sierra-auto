package com.mdc159.sierra.studio54

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Persistence for the sign-in and the secret cache. Implemented by encrypted prefs on the phone. */
interface Studio54Store {
    /**
     * Why the encrypted store could not be opened in this run of the app, or null. While
     * set, the store serves an in-memory copy and keeps the file on disk (never deleting
     * it); the next start tries the file again. See [EncryptedStudio54Store].
     */
    val openError: String?
        get() = null

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
class EncryptedStudio54Store(
    context: Context,
    /** How the encrypted file is opened; injectable so a test can simulate an open failure. */
    private val opener: (Context) -> SharedPreferences = ::openEncryptedStore,
) : Studio54Store {
    private val app = context.applicationContext

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Why the encrypted store could not be opened in this run, or null. While set, saves go
     * to an in-memory copy for this run: the file on disk is kept exactly as it is (it is
     * never deleted), and the next start of the app tries to open it again. Deleting the
     * file — the old behavior — turned a keystore reset into a silent sign-out.
     */
    @Volatile
    override var openError: String? = null
        private set

    /** The sign-in for this run when the store is unavailable. */
    @Volatile
    private var inMemoryCredentials: Studio54Credentials? = null

    /** The cache JSON for this run when the store is unavailable. */
    @Volatile
    private var inMemoryCacheJson: String? = null

    /** The encrypted store, or null when it cannot be opened in this run (see [openError]). */
    private fun prefs(): SharedPreferences? {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            if (openError != null) return null
            return try {
                opener(app).also { prefs = it }
            } catch (e: Exception) {
                // Keystore and file errors carry no credential values; the saved file is kept.
                openError = "${e.javaClass.simpleName}: ${redact(e.message)}"
                runCatching { Log.w(TAG, "studio54 store could not be opened; sign-in kept in memory for this run, file kept ($openError)") }
                null
            }
        }
    }

    override fun loadCredentials(): Studio54Credentials? {
        val p = prefs() ?: return inMemoryCredentials
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
        val p = prefs()
        if (p == null) {
            inMemoryCredentials = credentials
            return
        }
        p.edit()
            .putString(KEY_API_URL, credentials.apiUrl)
            .putString(KEY_CLIENT_ID, credentials.clientId)
            .putString(KEY_CLIENT_SECRET, credentials.clientSecret)
            .putString(KEY_PROJECT_ID, credentials.projectId)
            .putString(KEY_KEYS_PROJECT_ID, credentials.keysProjectId ?: "")
            .apply()
    }

    override fun loadCache(): SecretCache? {
        val p = prefs() ?: return inMemoryCacheJson?.let { SecretCache.fromJson(it) }
        return SecretCache.fromJson(p.getString(KEY_CACHE, null))
    }

    override fun saveCache(cache: SecretCache) {
        val p = prefs()
        if (p == null) {
            inMemoryCacheJson = cache.toJson()
            return
        }
        p.edit().putString(KEY_CACHE, cache.toJson()).apply()
    }

    override fun clear() {
        val p = prefs()
        if (p == null) {
            inMemoryCredentials = null
            inMemoryCacheJson = null
            return
        }
        // commit(), not apply(): "Sign out" must be complete before the screen says it is.
        p.edit().clear().commit()
    }

    /**
     * One line, bounded, with anything shaped like a credential or a query string removed
     * (the open error is also shown on the Studio 54 screen, so it must never carry a value).
     */
    private fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace(SECRET, "<redacted>")
            .replace(QUERY, "?<redacted>")
            .replace('\n', ' ')
            .take(120)
    }

    private val SECRET = Regex("(?i)\\b(?:sk|xi|pk)[-_][A-Za-z0-9_\\-*.]{6,}|(?<=bearer )[A-Za-z0-9_\\-.=]{8,}")
    private val QUERY = Regex("\\?[^\\s\"']+")

    private companion object {
        const val KEY_API_URL = "api_url"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_KEYS_PROJECT_ID = "keys_project_id"
        const val KEY_CACHE = "cache"
    }
}

/** The encrypted file [EncryptedStudio54Store] keeps its sign-in and cache in. */
private const val STORE_FILE = "sierra_studio54"

private const val TAG = "SierraStudio54"

/** The real open: EncryptedSharedPreferences under an AES-256-GCM master key in the Keystore. */
@Suppress("DEPRECATION")
private fun openEncryptedStore(context: Context): SharedPreferences {
    val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
        context,
        STORE_FILE,
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
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
