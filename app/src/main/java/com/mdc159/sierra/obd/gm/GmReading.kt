package com.mdc159.sierra.obd.gm

/**
 * The outcome of one Mode 22 read. Unlike the exceptions `Elm327Client` throws, a
 * negative response or `NO DATA` is an ordinary result here, because on an untested
 * DID list those answers are the data: they tell the owner which module to try next.
 */
sealed class GmReading<out T> {
    /** The module that was asked. */
    abstract val module: GmModule

    /** A positive `62 <did>` reply that decoded. */
    data class Value<T>(
        val value: T,
        override val module: GmModule,
        /** The data bytes after the DID echo, for logging while the DID is unverified. */
        val raw: List<Int> = emptyList(),
    ) : GmReading<T>()

    /** The module answered `7F 22 <nrc>`. [meaning] is the UDS name of the code. */
    data class NegativeResponse(
        val nrc: Int,
        override val module: GmModule,
    ) : GmReading<Nothing>() {
        val meaning: String get() = Nrc.describe(nrc)

        /** True when the code means "try another module or DID", not "the bus is broken". */
        val suggestsWrongModule: Boolean get() = nrc in Nrc.WRONG_MODULE_CODES
    }

    /** The adapter reported `NO DATA`, an error, or timed out: nobody answered. */
    data class NoReply(val reason: String, override val module: GmModule) : GmReading<Nothing>()

    /** Something answered, but not in a shape this DID expects (short frame, wrong echo). */
    data class Malformed(val reason: String, override val module: GmModule) : GmReading<Nothing>()

    val isValue: Boolean get() = this is Value

    /** The decoded value, or `null` for anything but [Value]. */
    fun valueOrNull(): T? = (this as? Value<T>)?.value
}

/** UDS negative response codes (ISO 14229-1) a Mode 22 read can get back. */
object Nrc {
    private val NAMES: Map<Int, String> = mapOf(
        0x10 to "generalReject",
        0x11 to "serviceNotSupported: this module does not do Mode 22 at this address",
        0x12 to "subFunctionNotSupported",
        0x13 to "incorrectMessageLengthOrInvalidFormat",
        0x14 to "responseTooLong",
        0x21 to "busyRepeatRequest",
        0x22 to "conditionsNotCorrect: engine state does not allow this read right now",
        0x24 to "requestSequenceError",
        0x31 to "requestOutOfRange: this module does not know this DID",
        0x33 to "securityAccessDenied: the DID needs a security session, which this app never opens",
        0x35 to "invalidKey",
        0x36 to "exceedNumberOfAttempts",
        0x37 to "requiredTimeDelayNotExpired",
        0x78 to "requestCorrectlyReceivedResponsePending",
        0x7E to "subFunctionNotSupportedInActiveSession",
        0x7F to "serviceNotSupportedInActiveSession",
    )

    /** Codes that mean "the DID or address is wrong", so a fallback module is worth a try. */
    val WRONG_MODULE_CODES: Set<Int> = setOf(0x11, 0x12, 0x31, 0x7F)

    fun describe(nrc: Int): String = NAMES[nrc and 0xFF] ?: "unknown negative response 0x%02X".format(nrc and 0xFF)
}
