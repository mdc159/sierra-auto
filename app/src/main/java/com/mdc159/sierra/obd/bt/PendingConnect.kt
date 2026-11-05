package com.mdc159.sierra.obd.bt

import java.io.Closeable

/**
 * Lets whoever asked for a connection abort it while the transport factory is still
 * blocked inside `BluetoothSocket.connect()` or waiting for a GATT link. A thread
 * interrupt does nothing to either; closing the pending socket or GATT does.
 *
 * The factory calls [register] with something whose `close()` aborts the attempt,
 * right before it blocks. [cancel] closes that object, or anything registered later.
 */
class PendingConnect {
    private val lock = Any()
    private var pending: Closeable? = null
    private var cancelled = false

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    /**
     * Registers the object to close on [cancel]. If a cancel already happened, closes
     * it now and throws so the factory stops.
     */
    fun register(closeable: Closeable) {
        val closeNow = synchronized(lock) {
            if (cancelled) true else { pending = closeable; false }
        }
        if (closeNow) {
            runCatching { closeable.close() }
            throw AdapterUnavailableException("Connection cancelled.")
        }
    }

    /** The attempt finished (either way); nothing is pending any more. */
    fun clear() {
        synchronized(lock) { pending = null }
    }

    /** Aborts the attempt in flight, if any, and refuses any later [register]. */
    fun cancel() {
        val c = synchronized(lock) {
            cancelled = true
            pending.also { pending = null }
        }
        c?.let { runCatching { it.close() } }
    }
}
