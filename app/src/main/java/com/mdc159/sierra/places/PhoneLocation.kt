package com.mdc159.sierra.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Reads the phone's current location through the platform LocationManager. */
object PhoneLocation {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns a recent fix: a last-known location under two minutes old if there is one,
     * otherwise a fresh fix (up to [timeoutMs]), otherwise any last-known location.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 10_000): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = lm.getProviders(true)
        val lastKnown = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        if (lastKnown != null && ageMs(lastKnown) < 120_000) return lastKnown

        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull()
        } ?: return lastKnown

        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(provider, { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }, context.mainLooper)
                    }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        return fresh ?: lastKnown
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
}
