package com.mdc159.sierra.studio54

/** What the status screen shows. Names and times only; never a value. */
class Studio54Status(
    val signedIn: Boolean,
    val vaultHost: String?,
    val projectId: String?,
    val keysProjectId: String?,
    val names: Set<String>,
    val lastFetched: Long?,
    val servingFromCache: Boolean,
    val lastError: String?,
)

/** Outcome of one [VaultSecretSource.refresh]. */
sealed class RefreshResult {
    /** The vault answered; [names] arrived. */
    class Fetched(val names: Set<String>) : RefreshResult()

    /** The vault could not be read; [cached] says whether cached secrets are still being served. */
    class Failed(val message: String, val unreachable: Boolean, val cached: Boolean) : RefreshResult()

    object NotSignedIn : RefreshResult()
}

/**
 * [SecretSource] backed by the Studio 54 vault with an encrypted on-device cache.
 *
 * Reads the agent's own project and, when granted, the shared keys project. A name in both
 * resolves to the agent's own value. When the vault is unreachable the last good read keeps
 * being served until it is [cacheMaxAgeMillis] old.
 */
class VaultSecretSource(
    private val store: Studio54Store,
    private val clientFactory: (Studio54Credentials) -> InfisicalClient = { InfisicalClient(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMaxAgeMillis: Long = DEFAULT_CACHE_MAX_AGE_MILLIS,
) : SecretSource {

    private val lock = Any()
    private var credentials: Studio54Credentials? = store.loadCredentials()
    private var client: InfisicalClient? = null
    private var cache: SecretCache? = store.loadCache()?.takeIf { it.projectId == credentials?.projectId }
    private var lastError: String? = null
    private var lastRefreshFailed = false

    val isSignedIn: Boolean get() = synchronized(lock) { credentials != null }

    override val lastFetched: Long? get() = synchronized(lock) { liveCache()?.fetchedAt }

    override fun get(name: String): String? = synchronized(lock) { liveCache()?.values?.get(name) }

    override fun names(): Set<String> = synchronized(lock) { liveCache()?.values?.keys?.toSortedSet() ?: emptySet() }

    /** Saves the sign-in (encrypted) and drops any cache that belonged to another project. */
    fun signIn(newCredentials: Studio54Credentials) {
        require(newCredentials.isComplete) { "Incomplete Studio 54 credentials." }
        synchronized(lock) {
            store.saveCredentials(newCredentials)
            credentials = newCredentials
            client = null
            if (cache?.projectId != newCredentials.projectId) cache = null
            lastError = null
            lastRefreshFailed = false
        }
    }

    /** Wipes the sign-in, the token and the cache. */
    fun signOut() {
        synchronized(lock) {
            store.clear()
            credentials = null
            client = null
            cache = null
            lastError = null
            lastRefreshFailed = false
        }
    }

    /**
     * Reads the vault now. Blocking: call it off the main thread. On failure the cached
     * secrets (if any and fresh enough) keep being served.
     */
    fun refresh(): RefreshResult {
        val creds = synchronized(lock) { credentials } ?: return RefreshResult.NotSignedIn
        return try {
            val c = synchronized(lock) { client ?: clientFactory(creds).also { client = it } }
            val merged = LinkedHashMap<String, String>()
            creds.keysProjectId?.let { merged.putAll(c.listSecrets(it)) }
            merged.putAll(c.listSecrets(creds.projectId))
            val fetched = SecretCache(creds.projectId, clock(), merged)
            synchronized(lock) {
                // Ignore the result if the owner signed out or changed sign-in meanwhile.
                if (credentials?.projectId == creds.projectId) {
                    cache = fetched
                    store.saveCache(fetched)
                    lastError = null
                    lastRefreshFailed = false
                }
            }
            RefreshResult.Fetched(fetched.values.keys.toSortedSet())
        } catch (e: VaultException) {
            val message = e.message ?: "Vault error."
            val serving = synchronized(lock) {
                lastError = message
                lastRefreshFailed = true
                if (e.code == 401) client = null
                liveCache() != null
            }
            RefreshResult.Failed(message, e.unreachable, serving)
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: "Vault URL is not valid."
            val serving = synchronized(lock) {
                lastError = message
                lastRefreshFailed = true
                liveCache() != null
            }
            RefreshResult.Failed(message, unreachable = false, cached = serving)
        }
    }

    fun status(): Studio54Status = synchronized(lock) {
        val creds = credentials
        val live = liveCache()
        Studio54Status(
            signedIn = creds != null,
            vaultHost = creds?.vaultHost,
            projectId = creds?.projectId,
            keysProjectId = creds?.keysProjectId,
            names = live?.values?.keys?.toSortedSet() ?: emptySet(),
            lastFetched = live?.fetchedAt,
            servingFromCache = live != null && lastRefreshFailed,
            lastError = lastError,
        )
    }

    /** The cache, unless it is too old to trust or belongs to no current sign-in. */
    private fun liveCache(): SecretCache? {
        val c = cache ?: return null
        if (credentials == null) return null
        return c.takeIf { it.isFresh(clock(), cacheMaxAgeMillis) }
    }

    companion object {
        /** How long cached secrets are served after the vault was last reached: 3 days. */
        const val DEFAULT_CACHE_MAX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
