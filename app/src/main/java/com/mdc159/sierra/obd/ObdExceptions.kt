package com.mdc159.sierra.obd

import java.io.IOException

/** Base class for every failure the OBD layer reports. */
open class ObdException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The adapter did not send its `>` prompt before the deadline. */
class ObdTimeoutException(val command: String, val timeoutMillis: Long, val partial: String) :
    ObdException("No prompt from adapter within ${timeoutMillis}ms for '$command' (got '${partial.trim()}')")

/** The link to the adapter closed mid-command. */
class ObdDisconnectedException(message: String) : ObdException(message)

/**
 * The adapter answered, but with an error such as `NO DATA`, `?`, `UNABLE TO CONNECT`
 * or `CAN ERROR`. [reason] holds the adapter's text.
 */
class ObdAdapterException(val command: String, val reason: String) :
    ObdException("Adapter rejected '$command': $reason")

/** An ECU sent a negative response (`7F <service> <code>`). */
class ObdNegativeResponseException(val service: Int, val code: Int) :
    ObdException("ECU negative response for service 0x%02X: 0x%02X".format(service, code))

/** The reply did not have the shape the request expects. */
class ObdParseException(message: String) : ObdException(message)

/** The command is not on the read-only allowlist. See [ObdSafety]. */
class ObdCommandBlockedException(val command: String, reason: String) :
    ObdException("Blocked '$command': $reason")
