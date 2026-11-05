package com.mdc159.sierra.obd.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.mdc159.sierra.obd.ObdTransport
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ObdTransport] over Bluetooth Low Energy for ELM327-class adapters that expose a
 * "UART" pair of characteristics: commands are written to one, replies arrive as
 * notifications on the other.
 *
 * **The service and characteristic UUIDs are unverified** (see [BleUartLayouts]). The
 * transport tries each candidate layout against what the device reports and uses the
 * first match; [layoutName] says which, so the adapter test on the phone can show it.
 *
 * Writes are chunked to the negotiated MTU and serialized: the next chunk goes out only
 * after the stack confirms the previous one. Replies are queued in a [ByteInbox] which
 * gives [read] its timeout semantics.
 */
class BluetoothLeTransport private constructor(private val link: Link) : ObdTransport {

    /** Which candidate layout matched, e.g. "Nordic UART (unverified)". */
    val layoutName: String? get() = link.layout?.name

    override fun write(bytes: ByteArray) {
        if (link.closed) throw IOException("Bluetooth LE link closed")
        for (chunk in BleFraming.chunks(bytes, link.payload)) link.writeChunk(chunk)
    }

    override fun read(buffer: ByteArray, timeoutMillis: Long): Int = link.inbox.read(buffer, timeoutMillis)

    override fun close() = link.close()

    /** GATT callback plus the state the transport needs. Callbacks arrive on binder threads. */
    @SuppressLint("MissingPermission")
    private class Link(private val label: String) : BluetoothGattCallback() {
        val inbox = ByteInbox()
        val ready = CountDownLatch(1)

        @Volatile var failure: String? = null
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var layout: BleUartLayout? = null
        @Volatile var payload: Int = BleFraming.DEFAULT_PAYLOAD
        @Volatile var closed = false

        private var writeChar: BluetoothGattCharacteristic? = null
        private val writeLock = Any()
        @Volatile private var writeLatch: CountDownLatch? = null
        @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS

        fun fail(message: String) {
            if (failure == null) failure = message
            Log.w(TAG, message)
            inbox.close()
            writeLatch?.countDown()
            ready.countDown()
        }

        fun close() {
            closed = true
            inbox.close()
            writeLatch?.countDown()
            ready.countDown()
            gatt?.let { g ->
                runCatching { g.disconnect() }
                runCatching { g.close() }
            }
            gatt = null
        }

        fun writeChunk(chunk: ByteArray) {
            val g = gatt ?: throw IOException("Bluetooth LE link closed")
            val c = writeChar ?: throw IOException("No write characteristic on $label")
            synchronized(writeLock) {
                val latch = CountDownLatch(1)
                writeLatch = latch
                writeStatus = BluetoothGatt.GATT_SUCCESS
                val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, chunk, c.writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.value = chunk
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                if (!issued) throw IOException("Bluetooth LE write refused by the stack")
                if (!latch.await(WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    throw IOException("Bluetooth LE write timed out")
                }
                if (closed || failure != null) throw IOException(failure ?: "Bluetooth LE link closed")
                if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                    throw IOException("Bluetooth LE write failed (status $writeStatus)")
                }
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.discoverServices()) fail("Couldn't discover services on $label.")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(
                    if (status == BluetoothGatt.GATT_SUCCESS) "$label disconnected."
                    else "$label disconnected (status $status). Is it plugged in?",
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Service discovery failed on $label (status $status).")
            val discovered = gatt.services.associate { s -> s.uuid to s.characteristics.map { it.uuid } }
            val picked = BleUartLayouts.pick(discovered)
                ?: return fail("$label has no known serial service. Its BLE layout isn't supported yet.")
            val service = gatt.getService(picked.service)
            val w = service?.getCharacteristic(picked.write)
            val n = service?.getCharacteristic(picked.notify)
            if (w == null || n == null) return fail("$label's serial characteristics went missing.")
            layout = picked
            w.writeType = if (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            writeChar = w
            Log.i(TAG, "$label: using layout ${picked.name}")
            if (!gatt.setCharacteristicNotification(n, true)) return fail("Couldn't listen to $label.")
            val cccd = n.getDescriptor(BleUartLayouts.CCCD)
            if (cccd == null) {
                // Some stacks notify without a CCCD write; treat the link as ready.
                ready.countDown()
                return
            }
            val value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            if (!issued) fail("Couldn't enable notifications on $label.")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != BleUartLayouts.CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Couldn't enable notifications on $label (status $status).")
            ready.countDown()
            // Bigger packets make multi-line replies arrive in fewer pieces. Best effort.
            runCatching { gatt.requestMtu(REQUESTED_MTU) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payload = BleFraming.payloadFor(mtu)
        }

        @Deprecated("Pre-API 33 callback; the three-argument overload is used on newer phones")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { inbox.offer(it) }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            inbox.offer(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeStatus = status
            writeLatch?.countDown()
        }
    }

    companion object {
        private const val TAG = "SierraBle"
        private const val REQUESTED_MTU = 247
        private const val WRITE_TIMEOUT_MILLIS = 3_000L

        /**
         * Connects, discovers services, picks a layout and enables notifications, or
         * throws [AdapterUnavailableException] with a car-screen message. Blocking: call
         * off the main thread. [pending] lets the caller abort the wait by closing the
         * GATT. Needs `BLUETOOTH_CONNECT` on API 31+; callers check
         * [BluetoothPermissions.hasConnect] first.
         */
        @SuppressLint("MissingPermission")
        fun connect(
            context: Context,
            device: BluetoothDevice,
            label: String,
            timeoutMillis: Long = 20_000,
            pending: PendingConnect = PendingConnect(),
        ): BluetoothLeTransport {
            val link = Link(label)
            pending.register(Closeable { link.close() })
            val gatt = try {
                device.connectGatt(context, false, link, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                throw AdapterUnavailableException("Allow Bluetooth for Sierra on the phone.", e)
            } ?: throw AdapterUnavailableException("Couldn't start a Bluetooth LE connection to $label.")
            link.gatt = gatt
            if (link.closed) {
                // Cancelled between register() and connectGatt(); release what we just opened.
                link.close()
                throw AdapterUnavailableException("Connection cancelled.")
            }
            val ready = link.ready.await(timeoutMillis, TimeUnit.MILLISECONDS)
            val failure = link.failure
            if (link.closed || pending.isCancelled) {
                link.close()
                throw AdapterUnavailableException("Connection cancelled.")
            }
            if (!ready || failure != null) {
                link.close()
                throw AdapterUnavailableException(
                    failure ?: "$label didn't answer over Bluetooth LE in ${timeoutMillis / 1000} s. Is it plugged in?",
                )
            }
            return BluetoothLeTransport(link)
        }
    }
}
