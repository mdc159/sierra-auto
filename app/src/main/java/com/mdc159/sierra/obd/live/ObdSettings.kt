package com.mdc159.sierra.obd.live

/** How Sierra reaches the truck. Chosen on the phone in ObdSettingsActivity. */
enum class AdapterKind {
    /** [DemoTransport]: a simulated engine, no hardware. The default until an adapter is picked. */
    DEMO,

    /** Bluetooth classic serial (RFCOMM, SPP) to a paired ELM327-class dongle. */
    CLASSIC,

    /** Bluetooth Low Energy "UART" to an ELM327-class dongle. Layouts are unverified; see obd/bt. */
    BLE,
}

/**
 * The chosen adapter. [address] is the Bluetooth MAC and [name] the device name shown
 * on the phone; both are blank in demo mode. Nothing here is secret.
 */
data class ObdSettings(
    val kind: AdapterKind = AdapterKind.DEMO,
    val address: String = "",
    val name: String = "",
) {
    val isDemo: Boolean get() = kind == AdapterKind.DEMO

    /** Short human label for screens and error messages. */
    val label: String
        get() = when (kind) {
            AdapterKind.DEMO -> "Demo adapter"
            AdapterKind.CLASSIC -> name.ifBlank { address }.ifBlank { "Bluetooth adapter" }
            AdapterKind.BLE -> name.ifBlank { address }.ifBlank { "Bluetooth LE adapter" } + " (LE)"
        }

    companion object {
        val DEMO = ObdSettings()
    }
}
