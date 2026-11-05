package com.mdc159.sierra.obd.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions Bluetooth needs on this phone's Android version.
 *
 * Android 12 (API 31) split Bluetooth into `BLUETOOTH_CONNECT` (talk to a paired or
 * known device) and `BLUETOOTH_SCAN` (discover devices). Before that, the install-time
 * `BLUETOOTH`/`BLUETOOTH_ADMIN` permissions cover connecting, and a BLE scan needs
 * fine location. Sierra's minimum is API 29, so both paths exist.
 */
object BluetoothPermissions {

    /** Permissions to ask for before connecting to an adapter. Empty when none is needed. */
    fun connect(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /** Permissions to ask for before a Bluetooth LE scan. */
    fun scan(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun all(): List<String> = (connect() + scan()).distinct()

    fun hasConnect(context: Context): Boolean = connect().all { granted(context, it) }

    fun hasScan(context: Context): Boolean = scan().all { granted(context, it) }

    fun missing(context: Context): List<String> = all().filterNot { granted(context, it) }

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
