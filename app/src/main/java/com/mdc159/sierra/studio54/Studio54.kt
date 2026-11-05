package com.mdc159.sierra.studio54

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-wide access to the Studio 54 sign-in. One [VaultSecretSource] per process, refreshed
 * when [start] is first called, every [REFRESH_INTERVAL_MILLIS] after that, and whenever a
 * caller reports that the gateway answered 401 ([onUnauthorized]).
 */
object Studio54 {
    /** Refresh on a timer: 30 minutes. */
    const val REFRESH_INTERVAL_MILLIS = 30L * 60 * 1000

    /** At most one 401-triggered refresh per minute, so a bad key cannot hammer the vault. */
    const val UNAUTHORIZED_DEBOUNCE_MILLIS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var source: VaultSecretSource? = null
    private var timer: Job? = null
    private var lastUnauthorizedRefresh = 0L

    /** The process-wide secret source. Reads the encrypted store the first time. */
    fun source(context: Context): VaultSecretSource {
        source?.let { return it }
        synchronized(this) {
            source?.let { return it }
            return VaultSecretSource(EncryptedStudio54Store(context)).also { source = it }
        }
    }

    /**
     * Starts the refresh-at-start and the timer. Safe to call from every entry point;
     * only the first call does anything. Does nothing when not signed in.
     */
    fun start(context: Context) {
        val s = source(context)
        synchronized(this) {
            if (timer?.isActive == true) return
            timer = scope.launch {
                while (isActive) {
                    if (s.isSignedIn) s.refresh()
                    delay(REFRESH_INTERVAL_MILLIS)
                }
            }
        }
    }

    /** Reads the vault now, off the main thread. */
    suspend fun refreshNow(context: Context): RefreshResult =
        withContext(Dispatchers.IO) { source(context).refresh() }

    /**
     * Call when the gateway answers 401 with a key from this source: the agent's key may have
     * been rotated on the server. Debounced; returns true if a refresh was started.
     */
    fun onUnauthorized(context: Context): Boolean {
        val s = source(context)
        if (!s.isSignedIn) return false
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastUnauthorizedRefresh < UNAUTHORIZED_DEBOUNCE_MILLIS) return false
            lastUnauthorizedRefresh = now
        }
        scope.launch { s.refresh() }
        return true
    }
}
