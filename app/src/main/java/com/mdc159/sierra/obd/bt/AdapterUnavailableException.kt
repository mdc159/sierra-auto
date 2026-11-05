package com.mdc159.sierra.obd.bt

import com.mdc159.sierra.obd.ObdException

/**
 * The adapter could not be reached at all: Bluetooth off, permission missing, device
 * not paired, connect timed out. The message is written for the car screen, so it is
 * short and tells the driver what to do on the phone. Not retried automatically.
 */
class AdapterUnavailableException(message: String, cause: Throwable? = null) : ObdException(message, cause)
