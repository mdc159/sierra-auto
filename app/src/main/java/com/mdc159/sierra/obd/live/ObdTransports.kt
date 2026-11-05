package com.mdc159.sierra.obd.live

import android.annotation.SuppressLint
import android.content.Context
import com.mdc159.sierra.obd.ObdTransport
import com.mdc159.sierra.obd.bt.AdapterUnavailableException
import com.mdc159.sierra.obd.bt.BluetoothClassicTransport
import com.mdc159.sierra.obd.bt.BluetoothLeTransport
import com.mdc159.sierra.obd.bt.BluetoothPermissions
import com.mdc159.sierra.obd.bt.PendingConnect

/**
 * Turns the phone-side choice in [ObdSettings] into an open [ObdTransport].
 *
 * Blocking for real adapters: call off the main thread; [pending] lets another
 * thread abort the wait. Every way it can fail throws [AdapterUnavailableException]
 * with a message that tells the driver what to do on the phone, because none of it
 * can be fixed from the car screen.
 */
object ObdTransports {

    @SuppressLint("MissingPermission") // Permission is checked explicitly below.
    fun open(context: Context, settings: ObdSettings, pending: PendingConnect = PendingConnect()): ObdTransport {
        if (settings.isDemo) return DemoTransport()

        val adapter = BluetoothPermissions.adapter(context)
            ?: throw AdapterUnavailableException("This phone has no Bluetooth.")
        if (!BluetoothPermissions.hasConnect(context)) {
            throw AdapterUnavailableException("Allow Bluetooth for Sierra in the phone app.")
        }
        if (!adapter.isEnabled) throw AdapterUnavailableException("Bluetooth is off on the phone.")
        if (settings.address.isBlank()) throw AdapterUnavailableException("Pick an adapter in Sierra on the phone.")

        val device = try {
            adapter.getRemoteDevice(settings.address.uppercase())
        } catch (e: IllegalArgumentException) {
            throw AdapterUnavailableException("Adapter address is invalid. Pick it again on the phone.", e)
        }

        return when (settings.kind) {
            AdapterKind.CLASSIC -> {
                val paired = runCatching { adapter.bondedDevices }.getOrNull()
                if (paired != null && paired.none { it.address.equals(device.address, ignoreCase = true) }) {
                    throw AdapterUnavailableException("${settings.label} isn't paired. Pair it in the phone's Bluetooth settings.")
                }
                BluetoothClassicTransport.connect(
                    device, settings.label,
                    beforeConnect = { runCatching { adapter.cancelDiscovery() } },
                    pending = pending,
                )
            }
            AdapterKind.BLE -> BluetoothLeTransport.connect(context, device, settings.label, pending = pending)
            AdapterKind.DEMO -> DemoTransport()
        }
    }
}
