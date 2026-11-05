package com.mdc159.sierra.obd.bt

import java.util.UUID

/**
 * One way an ELM327-class BLE adapter exposes its serial stream: a service, a
 * characteristic we write commands to, and a characteristic that notifies replies.
 * [write] and [notify] may be the same characteristic.
 */
data class BleUartLayout(
    val name: String,
    val service: UUID,
    val write: UUID,
    val notify: UUID,
)

/**
 * Candidate layouts, tried in order against the services a device reports.
 *
 * **UNVERIFIED.** None of these UUIDs has been checked against a real adapter on this
 * truck. They are collected from vendor app traffic and open-source OBD projects:
 * Nordic's UART service is what many "BLE UART" modules copy; `FFF0/FFF1/FFF2` and
 * `FFE0/FFE1` are the usual cheap-module layouts (Veepeak, vLinker, "OBDII" clones);
 * `18F0/2AF0/2AF1` shows up on a few newer clones; the long UUID is what OBDLink's
 * BLE products are reported to use. When a real adapter is connected, the chosen
 * layout name is logged and shown in the phone's adapter test so it can be recorded
 * in docs/OBD2-PLAN.md.
 */
object BleUartLayouts {
    /** Client Characteristic Configuration descriptor: write 0x0001 here to enable notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private fun short(hex16: String): UUID = UUID.fromString("0000$hex16-0000-1000-8000-00805F9B34FB")

    val CANDIDATES: List<BleUartLayout> = listOf(
        BleUartLayout(
            name = "Nordic UART (unverified)",
            service = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            write = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            notify = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
        ),
        BleUartLayout(
            name = "FFF0 generic ELM327 BLE (unverified)",
            service = short("FFF0"),
            write = short("FFF2"),
            notify = short("FFF1"),
        ),
        BleUartLayout(
            name = "FFE0 single characteristic (unverified)",
            service = short("FFE0"),
            write = short("FFE1"),
            notify = short("FFE1"),
        ),
        BleUartLayout(
            name = "18F0 / 2AF0 / 2AF1 (unverified)",
            service = short("18F0"),
            write = short("2AF1"),
            notify = short("2AF0"),
        ),
        BleUartLayout(
            name = "OBDLink-style custom service (unverified)",
            service = UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
            write = UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
            notify = UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
        ),
    )

    /**
     * Picks the first candidate whose service and both characteristics the device
     * reports. [discovered] maps each service UUID to its characteristic UUIDs.
     */
    fun pick(discovered: Map<UUID, Collection<UUID>>): BleUartLayout? =
        CANDIDATES.firstOrNull { layout ->
            val chars = discovered[layout.service] ?: return@firstOrNull false
            layout.write in chars && layout.notify in chars
        }
}

/** Splits a write into pieces no larger than the negotiated payload. */
object BleFraming {
    /** Payload per write with the default 23-byte MTU (3 bytes of ATT header). */
    const val DEFAULT_PAYLOAD = 20

    fun payloadFor(mtu: Int): Int = (mtu - 3).coerceIn(1, 512)

    fun chunks(bytes: ByteArray, maxPayload: Int): List<ByteArray> {
        require(maxPayload > 0) { "payload size must be positive" }
        if (bytes.isEmpty()) return emptyList()
        return (bytes.indices step maxPayload).map { start ->
            bytes.copyOfRange(start, minOf(start + maxPayload, bytes.size))
        }
    }
}
