package com.mdc159.sierra.obd

import java.io.Closeable
import java.io.IOException

/**
 * A raw byte pipe to an ELM327-class adapter.
 *
 * Implementations wrap a Bluetooth classic RFCOMM socket (SPP), a BLE GATT
 * characteristic pair, or a fake for tests. This interface knows nothing about
 * Android so the client and decoders can be unit tested on the JVM.
 */
interface ObdTransport : Closeable {

    /** Writes all of [bytes] to the adapter. Throws [IOException] if the link is down. */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /**
     * Reads up to `buffer.size` bytes, waiting at most [timeoutMillis].
     *
     * Returns the number of bytes read, `0` if nothing arrived before the timeout,
     * or `-1` if the link is closed. A [timeoutMillis] of `0` must not block.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMillis: Long): Int
}
