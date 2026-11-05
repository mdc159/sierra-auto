package com.mdc159.sierra.obd

/**
 * A Mode 01 parameter: its number, how many data bytes it returns and how to turn
 * those bytes into a value. Formulas follow SAE J1979; [ObdPids] lists the ones
 * worth having on a 2018 GMC Sierra (6.2L L86 V8).
 *
 * Values are SI/metric (km/h, °C, kPa). Convert at the display layer.
 */
class ObdPid<T>(
    val pid: Int,
    val name: String,
    val unit: String,
    val byteCount: Int,
    private val formula: (IntArray) -> T,
) {
    /** Decodes the data bytes that follow the `41 <pid>` echo. Extra bytes are ignored. */
    fun decode(data: ByteArray): T {
        if (data.size < byteCount) {
            throw ObdParseException(
                "PID 0x%02X ($name) needs $byteCount bytes, got ${data.size}".format(pid),
            )
        }
        return formula(IntArray(byteCount) { data[it].toInt() and 0xFF })
    }

    override fun toString(): String = "ObdPid(0x%02X %s)".format(pid, name)
}

/** PID 0x01: MIL lamp and how many confirmed codes the ECUs hold. */
data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)

object ObdPids {
    /** A, B as used in J1979 formulas. */
    private fun ab(v: IntArray) = v[0] * 256 + v[1]

    val MONITOR_STATUS = ObdPid(0x01, "Monitor status", "", 4) {
        MonitorStatus(milOn = it[0] and 0x80 != 0, dtcCount = it[0] and 0x7F)
    }
    val ENGINE_LOAD = ObdPid(0x04, "Calculated engine load", "%", 1) { it[0] * 100.0 / 255.0 }
    val COOLANT_TEMP = ObdPid(0x05, "Coolant temperature", "°C", 1) { it[0] - 40.0 }
    val SHORT_FUEL_TRIM_B1 = ObdPid(0x06, "Short term fuel trim bank 1", "%", 1) { (it[0] - 128) * 100.0 / 128.0 }
    val LONG_FUEL_TRIM_B1 = ObdPid(0x07, "Long term fuel trim bank 1", "%", 1) { (it[0] - 128) * 100.0 / 128.0 }
    val SHORT_FUEL_TRIM_B2 = ObdPid(0x08, "Short term fuel trim bank 2", "%", 1) { (it[0] - 128) * 100.0 / 128.0 }
    val LONG_FUEL_TRIM_B2 = ObdPid(0x09, "Long term fuel trim bank 2", "%", 1) { (it[0] - 128) * 100.0 / 128.0 }
    val INTAKE_MAP = ObdPid(0x0B, "Intake manifold pressure", "kPa", 1) { it[0].toDouble() }
    val ENGINE_RPM = ObdPid(0x0C, "Engine speed", "rpm", 2) { ab(it) / 4.0 }
    val VEHICLE_SPEED = ObdPid(0x0D, "Vehicle speed", "km/h", 1) { it[0].toDouble() }
    val TIMING_ADVANCE = ObdPid(0x0E, "Timing advance", "° BTDC", 1) { it[0] / 2.0 - 64.0 }
    val INTAKE_AIR_TEMP = ObdPid(0x0F, "Intake air temperature", "°C", 1) { it[0] - 40.0 }
    val MAF_RATE = ObdPid(0x10, "Mass air flow", "g/s", 2) { ab(it) / 100.0 }
    val THROTTLE_POSITION = ObdPid(0x11, "Throttle position", "%", 1) { it[0] * 100.0 / 255.0 }
    val RUN_TIME = ObdPid(0x1F, "Run time since start", "s", 2) { ab(it).toDouble() }
    val DISTANCE_WITH_MIL = ObdPid(0x21, "Distance with MIL on", "km", 2) { ab(it).toDouble() }
    val FUEL_LEVEL = ObdPid(0x2F, "Fuel level", "%", 1) { it[0] * 100.0 / 255.0 }
    val DISTANCE_SINCE_CLEAR = ObdPid(0x31, "Distance since codes cleared", "km", 2) { ab(it).toDouble() }
    val BAROMETRIC_PRESSURE = ObdPid(0x33, "Barometric pressure", "kPa", 1) { it[0].toDouble() }
    val CATALYST_TEMP_B1S1 = ObdPid(0x3C, "Catalyst temperature B1S1", "°C", 2) { ab(it) / 10.0 - 40.0 }
    val CONTROL_MODULE_VOLTAGE = ObdPid(0x42, "Control module voltage", "V", 2) { ab(it) / 1000.0 }
    val ABSOLUTE_LOAD = ObdPid(0x43, "Absolute load", "%", 2) { ab(it) * 100.0 / 255.0 }
    val AMBIENT_AIR_TEMP = ObdPid(0x46, "Ambient air temperature", "°C", 1) { it[0] - 40.0 }
    val ENGINE_OIL_TEMP = ObdPid(0x5C, "Engine oil temperature", "°C", 1) { it[0] - 40.0 }
    val ENGINE_FUEL_RATE = ObdPid(0x5E, "Engine fuel rate", "L/h", 2) { ab(it) / 20.0 }

    /** Every PID above, keyed by number. */
    val ALL: Map<Int, ObdPid<*>> = listOf(
        MONITOR_STATUS, ENGINE_LOAD, COOLANT_TEMP,
        SHORT_FUEL_TRIM_B1, LONG_FUEL_TRIM_B1, SHORT_FUEL_TRIM_B2, LONG_FUEL_TRIM_B2,
        INTAKE_MAP, ENGINE_RPM, VEHICLE_SPEED, TIMING_ADVANCE, INTAKE_AIR_TEMP,
        MAF_RATE, THROTTLE_POSITION, RUN_TIME, DISTANCE_WITH_MIL, FUEL_LEVEL,
        DISTANCE_SINCE_CLEAR, BAROMETRIC_PRESSURE, CATALYST_TEMP_B1S1,
        CONTROL_MODULE_VOLTAGE, ABSOLUTE_LOAD, AMBIENT_AIR_TEMP, ENGINE_OIL_TEMP,
        ENGINE_FUEL_RATE,
    ).associateBy { it.pid }

    /**
     * Decodes a "PIDs supported" bitmap (PID 0x00, 0x20, 0x40, ...).
     * Bit 7 of the first byte is PID base+1; the last bit is base+0x20.
     */
    fun decodeSupported(base: Int, data: ByteArray): Set<Int> {
        if (data.size < 4) throw ObdParseException("Supported-PID bitmap needs 4 bytes, got ${data.size}")
        val result = mutableSetOf<Int>()
        for (i in 0 until 32) {
            val byte = data[i / 8].toInt() and 0xFF
            if (byte and (0x80 ushr (i % 8)) != 0) result += base + i + 1
        }
        return result
    }
}

/**
 * A GM manufacturer-specific Mode 22 data identifier.
 *
 * **Unverified.** These come from community sources, not from GM documentation,
 * and none has been confirmed on this truck yet. [verified] stays `false` until a
 * reading has been checked against a scan tool or the dash. The UI must label any
 * value from an unverified PID as such.
 */
class GmExtendedPid<T>(
    val did: Int,
    /** CAN request header of the module that answers, e.g. "7E2" for the TCM. */
    val header: String,
    val name: String,
    val unit: String,
    val byteCount: Int,
    val verified: Boolean,
    private val formula: (IntArray) -> T,
) {
    init {
        require(header in ObdSafety.ALLOWED_HEADERS) { "Header $header is not allowed" }
        require(did in 0..0xFFFF) { "DID must fit in two bytes" }
    }

    fun decode(data: ByteArray): T {
        if (data.size < byteCount) {
            throw ObdParseException("DID 0x%04X ($name) needs $byteCount bytes, got ${data.size}".format(did))
        }
        return formula(IntArray(byteCount) { data[it].toInt() and 0xFF })
    }

    override fun toString(): String = "GmExtendedPid(0x%04X %s @%s)".format(did, name, header)
}

object GmExtendedPids {
    /** Transmission fluid temperature from the TCM. Commonly cited as 22 1940, A-40 °C. UNVERIFIED. */
    val TRANS_FLUID_TEMP = GmExtendedPid(0x1940, "7E2", "Transmission fluid temperature", "°C", 1, verified = false) {
        it[0] - 40.0
    }

    /** Engine oil pressure from the ECM. Commonly cited as 22 115C, A*4 kPa. UNVERIFIED. */
    val OIL_PRESSURE = GmExtendedPid(0x115C, "7E0", "Engine oil pressure", "kPa", 1, verified = false) {
        it[0] * 4.0
    }

    /** Engine oil life remaining from the ECM. Commonly cited as 22 119C, A*100/255 %. UNVERIFIED. */
    val OIL_LIFE = GmExtendedPid(0x119C, "7E0", "Engine oil life remaining", "%", 1, verified = false) {
        it[0] * 100.0 / 255.0
    }

    val ALL: List<GmExtendedPid<Double>> = listOf(TRANS_FLUID_TEMP, OIL_PRESSURE, OIL_LIFE)
}
