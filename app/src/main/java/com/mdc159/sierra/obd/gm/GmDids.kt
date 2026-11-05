package com.mdc159.sierra.obd.gm

/**
 * GM Mode 22 DIDs for the 2018 Sierra 1500 (K2XX, L83/L86, 8L90) whose request
 * bytes are backed by two or more independent public sources. The research, the
 * sources behind each `sources` label and the on-truck test plan are in
 * docs/GM-PIDS.md. Every value here is **unverified on the truck**.
 *
 * Single-source and guessed DIDs are deliberately not here; they are listed in the
 * doc only, so nothing in code can be mistaken for a tested reading.
 */
object GmDids {

    /**
     * Transmission fluid temperature from the TCM. The most-cited GM extended PID
     * of all: Torque's predefined GM set ("Method 2" = header 7E2), ScanGauge's GM
     * CANSF X-Gauge, the Colorado/Canyon and GMT900 community lists all agree on
     * `22 1940`, one byte, A − 40 °C. Some vehicles answer it from the ECM instead.
     */
    val TRANS_FLUID_TEMP = GmDid(
        did = 0x1940,
        module = GmModule.TCM,
        fallbackModules = listOf(GmModule.ECM, GmModule.TCM_ALT),
        name = "Transmission fluid temperature",
        unit = "°C",
        byteCount = 1,
        didConfidence = Confidence.CONFIRMED,
        formulaConfidence = Confidence.CONFIRMED,
        pollIntervalMillis = 5_000,
        sources = listOf("torque-gm-set", "scangauge-gm", "coloradofans", "gmtnation", "python-obd-287"),
    ) { it[0] - 40.0 }

    /**
     * Engine oil temperature from the ECM: `22 1154`, A − 40 °C. Reported to match
     * the dash on a GMT900 and to work on a 2015 Chevy SS. Compare it with the
     * standard Mode 01 PID 0x5C, which the E92 ECM should also support.
     */
    val ENGINE_OIL_TEMP = GmDid(
        did = 0x1154,
        module = GmModule.ECM,
        name = "Engine oil temperature",
        unit = "°C",
        byteCount = 1,
        didConfidence = Confidence.CONFIRMED,
        formulaConfidence = Confidence.CONFIRMED,
        pollIntervalMillis = 5_000,
        sources = listOf("torque-gm-set", "harrys-gps", "ssforums", "coloradofans", "gmtnation"),
    ) { it[0] - 40.0 }

    /**
     * Engine oil pressure from the ECM: `22 1470`, one byte, 4 kPa per count
     * (0.58 psi per count). Three sources give the same scale in different units:
     * Torque's GM set (A × 0.578 psi, confirmed on a 2015 Chevy SS), ScanGauge's
     * Duramax X-Gauge (MTH 29/50 psi per count) and a GMT900 list (A × 3.985,
     * which is the kPa scale with the unit mislabelled). Read as gauge pressure.
     */
    val ENGINE_OIL_PRESSURE = GmDid(
        did = 0x1470,
        module = GmModule.ECM,
        name = "Engine oil pressure",
        unit = "kPa",
        byteCount = 1,
        didConfidence = Confidence.CONFIRMED,
        formulaConfidence = Confidence.CONFIRMED,
        pollIntervalMillis = 1_000,
        sources = listOf("torque-gm-set", "ssforums", "scangauge-gm", "gmtnation"),
    ) { it[0] * 4.0 }

    /**
     * The raw oil pressure sensor DID: `22 115C`, one byte. Many community posts use
     * it, but each with a different psi equation found by trial and error. The best
     * fit across them is 4 kPa per count of *absolute* pressure (A × 0.578 − 14.7 psi
     * is exactly A × 4 kPa minus one atmosphere), so this decoder returns kPa
     * absolute and the display must subtract barometric pressure (Mode 01 PID 0x33)
     * to compare with the gauge. Formula confidence is single-source for that reason.
     */
    val OIL_PRESSURE_SENSOR = GmDid(
        did = 0x115C,
        module = GmModule.ECM,
        name = "Engine oil pressure sensor (absolute)",
        unit = "kPa",
        byteCount = 1,
        didConfidence = Confidence.CONFIRMED,
        formulaConfidence = Confidence.SINGLE_SOURCE,
        pollIntervalMillis = 1_000,
        sources = listOf("tahoeyukon", "coloradofans-oilp", "chevroletforum", "scangauge-gm"),
    ) { it[0] * 4.0 }

    /**
     * Remaining engine oil life from the ECM: `22 119F`, A × 100 / 255 %. ScanGauge's
     * "GM specific CANSF remaining oil life" X-Gauge and a GMT900 Torque setup
     * (A / 2.55) agree. Changes once per drive at most, so read it once.
     */
    val OIL_LIFE = GmDid(
        did = 0x119F,
        module = GmModule.ECM,
        name = "Engine oil life remaining",
        unit = "%",
        byteCount = 1,
        didConfidence = Confidence.CONFIRMED,
        formulaConfidence = Confidence.CONFIRMED,
        pollIntervalMillis = null,
        sources = listOf("scangauge-oil-life", "gmtnation-envoy"),
    ) { it[0] * 100.0 / 255.0 }

    /**
     * Current (commanded) transmission gear: `22 199A`, one byte, the gear number
     * as-is. Two lists agree on the DID and the "A" formula; one says the ECM answers,
     * one leaves the header blank, and one user reports a value of 3 in Park. Which
     * module answers on the 8L90 and what Park/Neutral read are open questions.
     */
    val CURRENT_GEAR = GmDid(
        did = 0x199A,
        module = GmModule.ECM,
        fallbackModules = listOf(GmModule.TCM),
        name = "Current gear",
        unit = "gear",
        byteCount = 1,
        didConfidence = Confidence.CONFIRMED,
        formulaConfidence = Confidence.SINGLE_SOURCE,
        pollIntervalMillis = 1_000,
        sources = listOf("coloradofans", "torque-bhp-gm-pids"),
    ) { it[0] }

    /** Every DID above. */
    val ALL: List<GmDid<*>> = listOf(
        TRANS_FLUID_TEMP, ENGINE_OIL_TEMP, ENGINE_OIL_PRESSURE, OIL_PRESSURE_SENSOR, OIL_LIFE, CURRENT_GEAR,
    )

    /** DIDs worth polling repeatedly while driving, with an interval. */
    val LIVE: List<GmDid<*>> = ALL.filter { it.pollIntervalMillis != null }

    /** DIDs to read once at the start of a trip. */
    val ONCE_PER_TRIP: List<GmDid<*>> = ALL.filter { it.pollIntervalMillis == null }

    /** The three to try first on the truck, in order. See the checklist in docs/GM-PIDS.md. */
    val FIRST_TEST: List<GmDid<*>> = listOf(TRANS_FLUID_TEMP, ENGINE_OIL_PRESSURE, ENGINE_OIL_TEMP)

    /** Looks a DID up by number, ignoring which module it belongs to. */
    fun byDid(did: Int): GmDid<*>? = ALL.firstOrNull { it.did == did }
}
