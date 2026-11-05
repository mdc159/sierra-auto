package com.mdc159.sierra.obd.gm

import com.mdc159.sierra.obd.ObdSafety

/**
 * The GM powertrain modules a Mode 22 request can be addressed to, with the CAN
 * identifiers the ELM327 uses for them. The adapter sends on [requestHeader]
 * (`AT SH`), and with CAN auto-formatting on it listens for the physical reply on
 * [responseId] (request + 8) by itself.
 *
 * Every header here must be on [ObdSafety.ALLOWED_HEADERS]; the init block proves it.
 */
enum class GmModule(val requestHeader: String, val responseId: String, val label: String) {
    /** Engine control module (E92 on the 2014-2018 5.3L/6.2L trucks). */
    ECM("7E0", "7E8", "Engine control module"),

    /** Transmission control module. Where GM trucks usually answer transmission DIDs. */
    TCM("7E2", "7EA", "Transmission control module"),

    /** Second powertrain address. Some GM vehicles put the TCM here instead of 7E2. */
    TCM_ALT("7E1", "7E9", "Alternate transmission module address");

    init {
        require(requestHeader in ObdSafety.ALLOWED_HEADERS) { "$requestHeader is not an allowed header" }
    }

    companion object {
        /** The module that answers on [requestHeader], if it is one we know. */
        fun forHeader(requestHeader: String): GmModule? =
            entries.firstOrNull { it.requestHeader.equals(requestHeader, ignoreCase = true) }
    }
}
