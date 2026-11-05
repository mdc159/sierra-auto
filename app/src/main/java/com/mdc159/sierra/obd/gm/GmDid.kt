package com.mdc159.sierra.obd.gm

import com.mdc159.sierra.obd.GmExtendedPid
import com.mdc159.sierra.obd.ObdParseException
import com.mdc159.sierra.obd.ObdSafety

/**
 * How well a value in a [GmDid] is backed by public sources. See docs/GM-PIDS.md for
 * the sources themselves. None of this is verified on the owner's truck yet.
 */
enum class Confidence {
    /** Two or more independent sources agree. */
    CONFIRMED,

    /** One source, or several that copy each other. */
    SINGLE_SOURCE,

    /** Inferred, not found in any source. */
    GUESS,
}

/**
 * A GM manufacturer-specific Mode 22 (UDS ReadDataByIdentifier) data identifier
 * and how to decode its reply.
 *
 * The request on the wire is `22 <did high> <did low>`, sent to [module]'s header.
 * A positive reply is `62 <did high> <did low> <data...>`; [decode] takes the data
 * bytes after the DID echo. Formulas follow the `ObdPid` convention: `A` is the
 * first data byte, values are SI (°C, kPa), and the display layer converts.
 *
 * **Unverified on the truck.** [didConfidence] and [formulaConfidence] describe
 * the public sources only. Anything shown on a screen from one of these must be
 * labelled as unverified until the owner has compared it with the dash.
 */
class GmDid<T>(
    val did: Int,
    /** The module the request is normally sent to. */
    val module: GmModule,
    /** Other modules worth trying if [module] answers `7F 22 31` or `NO DATA`. */
    val fallbackModules: List<GmModule> = emptyList(),
    val name: String,
    val unit: String,
    /** Data bytes the formula needs, after the `62 DD DD` echo. Extra bytes are ignored. */
    val byteCount: Int,
    val didConfidence: Confidence,
    val formulaConfidence: Confidence,
    /**
     * Suggested read interval while driving, or `null` for "read once per trip".
     * Every DID here is a read; the interval is about bus load and usefulness,
     * not safety.
     */
    val pollIntervalMillis: Long?,
    /** Short labels of the sources in docs/GM-PIDS.md that back this entry. */
    val sources: List<String>,
    private val formula: (IntArray) -> T,
) {
    init {
        require(did in 0..0xFFFF) { "DID must fit in two bytes" }
        require(byteCount >= 1) { "byteCount must be at least 1" }
        require(module !in fallbackModules) { "fallbackModules must not repeat the primary module" }
    }

    /** The hex request string, e.g. `221940`. */
    val request: String = "22%04X".format(did)

    init {
        // Prove at construction that the read-only allowlist accepts this request as-is.
        ObdSafety.blockReason(request)?.let { throw IllegalArgumentException("$request is blocked: $it") }
    }

    /** The two DID bytes as the module echoes them. */
    val didHigh: Int get() = (did ushr 8) and 0xFF
    val didLow: Int get() = did and 0xFF

    /** Decodes the data bytes that follow the `62 <did>` echo. */
    fun decode(data: ByteArray): T {
        if (data.size < byteCount) {
            throw ObdParseException(
                "DID 0x%04X ($name) needs $byteCount bytes, got ${data.size}".format(did),
            )
        }
        return formula(IntArray(byteCount) { data[it].toInt() and 0xFF })
    }

    /**
     * The same definition in the shape `Elm327Client.readExtended` takes, addressed
     * to [target]. `verified` stays false: that flag is for readings checked on the
     * truck, which has not happened for any DID here.
     */
    fun toExtendedPid(target: GmModule = module): GmExtendedPid<T> =
        GmExtendedPid(did, target.requestHeader, name, unit, byteCount, verified = false, formula = formula)

    override fun toString(): String = "GmDid(0x%04X %s @%s)".format(did, name, module.requestHeader)
}
