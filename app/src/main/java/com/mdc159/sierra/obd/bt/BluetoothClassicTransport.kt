package com.mdc159.sierra.obd.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.mdc159.sierra.obd.ObdTransport
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.UUID

/**
 * [ObdTransport] over a Bluetooth classic RFCOMM socket (the Serial Port Profile every
 * ELM327 dongle exposes).
 *
 * `BluetoothSocket` streams have no read timeout, so [read] polls `available()` and
 * sleeps in short slices until the deadline. That keeps the client's "every wait has a
 * deadline" rule without a second thread.
 */
class BluetoothClassicTransport private constructor(
    private val socket: BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
) : ObdTransport {

    @Volatile
    private var closed = false

    override fun write(bytes: ByteArray) {
        if (closed) throw IOException("Bluetooth socket closed")
        output.write(bytes)
        output.flush()
    }

    override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
        if (closed) return -1
        if (buffer.isEmpty()) return 0
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            if (closed) return -1
            val available = try {
                input.available()
            } catch (e: IOException) {
                if (closed) return -1 else throw e
            }
            if (available > 0) {
                val n = input.read(buffer, 0, minOf(available, buffer.size))
                return if (n < 0) -1 else n
            }
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return 0
            try {
                Thread.sleep(minOf(remaining, POLL_SLICE_MILLIS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("interrupted while waiting for the adapter")
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        /** Standard Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val POLL_SLICE_MILLIS = 10L

        /**
         * Opens the SPP channel to [device]. Blocking: call off the main thread. A
         * watchdog closes the socket at [timeoutMillis] so a dongle that is paired but
         * unplugged does not hang the caller for the stack's own (long) timeout.
         *
         * Needs `BLUETOOTH_CONNECT` on API 31+; callers check [BluetoothPermissions.hasConnect]
         * first, which is why the lint check is suppressed here.
         */
        @SuppressLint("MissingPermission")
        fun connect(
            device: BluetoothDevice,
            label: String,
            timeoutMillis: Long = 15_000,
            beforeConnect: () -> Unit = {},
        ): BluetoothClassicTransport {
            val socket = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: IOException) {
                throw AdapterUnavailableException("Couldn't open a Bluetooth channel to $label.", e)
            } catch (e: SecurityException) {
                throw AdapterUnavailableException("Allow Bluetooth for Sierra on the phone.", e)
            }
            beforeConnect()
            val lock = Any()
            var done = false
            var timedOut = false
            val watchdog = Thread({
                try {
                    Thread.sleep(timeoutMillis)
                    synchronized(lock) {
                        if (!done) {
                            timedOut = true
                            runCatching { socket.close() }
                        }
                    }
                } catch (e: InterruptedException) {
                    // Connect finished first.
                }
            }, "sierra-bt-connect-watchdog").apply { isDaemon = true }
            watchdog.start()
            try {
                socket.connect()
                synchronized(lock) { done = true }
            } catch (e: IOException) {
                val wasTimeout = synchronized(lock) { done = true; timedOut }
                runCatching { socket.close() }
                if (wasTimeout) {
                    throw AdapterUnavailableException(
                        "$label didn't answer in ${timeoutMillis / 1000} s. Is it plugged in and the key on?", e,
                    )
                }
                throw AdapterUnavailableException("Couldn't connect to $label. Is it plugged in and paired?", e)
            } catch (e: SecurityException) {
                runCatching { socket.close() }
                throw AdapterUnavailableException("Allow Bluetooth for Sierra on the phone.", e)
            } finally {
                watchdog.interrupt()
            }
            return BluetoothClassicTransport(socket, socket.inputStream, socket.outputStream)
        }
    }
}
