package com.mdc159.sierra.obd.live

import java.util.Locale
import kotlin.math.roundToInt

/**
 * One poll of the live values the Truck screen shows. Metric, straight from the PID
 * decoders; `null` means the truck does not report that PID or the read failed this
 * time. Display conversion is in [TruckFormat].
 */
data class TruckReadings(
    val rpm: Double? = null,
    val speedKmh: Double? = null,
    val coolantC: Double? = null,
    val intakeC: Double? = null,
    val loadPct: Double? = null,
    val fuelPct: Double? = null,
    val voltage: Double? = null,
    /** How many of the values above came back this poll. */
    val okCount: Int = 0,
    val updatedAtMillis: Long = 0,
)

/** US units for the truck screen. Short strings: the host truncates row text while driving. */
object TruckFormat {
    const val NA = "n/a"

    fun rpm(v: Double?): String = v?.let { String.format(Locale.US, "%,d rpm", it.roundToInt()) } ?: NA

    fun mph(kmh: Double?): String = kmh?.let { "${(it / 1.609344).roundToInt()} mph" } ?: NA

    fun fahrenheit(c: Double?): String = c?.let { "${(it * 9.0 / 5.0 + 32).roundToInt()} °F" } ?: NA

    fun percent(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: NA

    fun volts(v: Double?): String = v?.let { String.format(Locale.US, "%.1f V", it) } ?: NA
}
