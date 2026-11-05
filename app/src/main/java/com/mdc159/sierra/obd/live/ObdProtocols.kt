package com.mdc159.sierra.obd.live

/** Names for the ELM327 `ATDPN` protocol numbers, for the settings screen. */
object ObdProtocols {
    private val NAMES = mapOf(
        "0" to "Automatic",
        "1" to "SAE J1850 PWM (41.6 kbaud)",
        "2" to "SAE J1850 VPW (10.4 kbaud)",
        "3" to "ISO 9141-2",
        "4" to "ISO 14230-4 KWP (5 baud init)",
        "5" to "ISO 14230-4 KWP (fast init)",
        "6" to "ISO 15765-4 CAN (11-bit, 500 kbaud)",
        "7" to "ISO 15765-4 CAN (29-bit, 500 kbaud)",
        "8" to "ISO 15765-4 CAN (11-bit, 250 kbaud)",
        "9" to "ISO 15765-4 CAN (29-bit, 250 kbaud)",
        "A" to "SAE J1939 CAN (29-bit, 250 kbaud)",
        "B" to "User1 CAN",
        "C" to "User2 CAN",
    )

    /** `6` → "6: ISO 15765-4 CAN (11-bit, 500 kbaud)", `A6` → the same plus "auto-detected". */
    fun describe(code: String): String {
        val c = code.trim().uppercase()
        if (c.isEmpty()) return "unknown"
        val auto = c.length == 2 && c[0] == 'A'
        val number = if (auto) c.substring(1) else c
        val name = NAMES[number] ?: return "unknown ($code)"
        return if (auto) "$number: $name, auto-detected" else "$number: $name"
    }
}
