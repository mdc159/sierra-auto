package com.mdc159.sierra.obd

/**
 * Read-only allowlist for everything sent to the adapter.
 *
 * The rule is "deny unless listed". Only commands that read data, plus the ELM327
 * setup commands needed to talk at all, pass. Anything that could write to an ECU,
 * run a routine, unlock security access, change adapter non-volatile settings or
 * address an unknown module is refused before it reaches the wire.
 *
 * Mode 04 (clear DTCs and freeze frame) is the single exception: it passes only when
 * the caller explicitly opts in, which [Elm327Client.clearDiagnosticCodes] does. See
 * docs/OBD2-PLAN.md for the rules around using it.
 */
object ObdSafety {

    /**
     * CAN request headers we may address with `AT SH`.
     * 7DF = functional broadcast, 7E0 = ECM, 7E1 = TCM on some GM, 7E2 = TCM on most GM.
     */
    val ALLOWED_HEADERS: Set<String> = setOf("7DF", "7E0", "7E1", "7E2")

    private val AT_EXACT: Set<String> = setOf(
        "Z", "WS", "D", "I", "@1", "RV", "DP", "DPN",
        "E0", "E1", "L0", "L1", "S0", "S1", "H0", "H1",
        "CAF0", "CAF1", "AT0", "AT1", "AT2",
    )

    private val AT_PROTOCOL = Regex("SP[0-9A-C]")
    private val AT_TIMEOUT = Regex("ST[0-9A-F]{2}")
    private val AT_HEADER = Regex("SH([0-9A-F]{3})")

    /** Services that only read. 0x04 is handled separately. */
    private val READ_SERVICES: Set<Int> = setOf(0x01, 0x03, 0x07, 0x09, 0x0A, 0x22)

    /** Normalizes a command the way the ELM327 sees it: upper case, no spaces. */
    fun normalize(command: String): String = command.uppercase().replace(" ", "").trim()

    /** Throws [ObdCommandBlockedException] unless [command] is on the allowlist. */
    fun requireAllowed(command: String, allowClearCodes: Boolean = false) {
        val reason = blockReason(command, allowClearCodes)
        if (reason != null) throw ObdCommandBlockedException(command, reason)
    }

    /** Returns `null` if [command] may be sent, otherwise why not. */
    fun blockReason(command: String, allowClearCodes: Boolean = false): String? {
        val c = normalize(command)
        if (c.isEmpty()) return "empty command"
        if (c.any { it == '\r' || it == '\n' }) return "embedded line break"
        if (c.startsWith("AT")) return atBlockReason(c.removePrefix("AT"))
        return obdBlockReason(c, allowClearCodes)
    }

    private fun atBlockReason(at: String): String? {
        if (at in AT_EXACT) return null
        if (AT_PROTOCOL.matches(at)) return null
        if (AT_TIMEOUT.matches(at)) return null
        val header = AT_HEADER.matchEntire(at)
        if (header != null) {
            val h = header.groupValues[1]
            return if (h in ALLOWED_HEADERS) null else "header $h is not an allowed ECU address"
        }
        return "AT command 'AT$at' is not on the read-only allowlist"
    }

    private fun obdBlockReason(c: String, allowClearCodes: Boolean): String? {
        if (!c.all { it in '0'..'9' || it in 'A'..'F' }) return "not a hex OBD request"
        if (c.length % 2 != 0) return "odd number of hex digits"
        if (c.length > 14) return "request too long for a single CAN frame"
        val service = c.substring(0, 2).toInt(16)
        val data = c.substring(2)
        return when (service) {
            0x01 -> if (data.length in 2..12) null else "mode 01 needs 1-6 PIDs"
            0x03, 0x07, 0x0A -> if (data.isEmpty()) null else "mode %02X takes no data".format(service)
            0x09 -> if (data.length == 2) null else "mode 09 needs one PID"
            0x22 -> if (data.length == 4) null else "mode 22 needs one 2-byte DID"
            0x04 -> when {
                !allowClearCodes -> "mode 04 clears codes; use clearDiagnosticCodes()"
                data.isNotEmpty() -> "mode 04 takes no data"
                else -> null
            }
            else -> if (service in READ_SERVICES) null else "service 0x%02X is not read-only".format(service)
        }
    }
}
