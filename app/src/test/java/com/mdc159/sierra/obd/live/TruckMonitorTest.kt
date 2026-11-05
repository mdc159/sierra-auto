package com.mdc159.sierra.obd.live

import com.mdc159.sierra.obd.Dtc
import com.mdc159.sierra.obd.ObdTransport
import com.mdc159.sierra.obd.bt.AdapterUnavailableException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TruckMonitorTest {

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun demoAdapterGoesLiveAndPublishesReadings() {
        val monitor = TruckMonitor({ DemoTransport() }, pollIntervalMillis = 10)
        monitor.start()
        try {
            val live = await { monitor.state.first { it is TruckState.Live } } as TruckState.Live
            assertEquals(DemoTransport.BANNER, live.adapter.identity)
            assertNotNull(live.readings.rpm)
            assertNotNull(live.readings.speedKmh)
            assertNotNull(live.readings.coolantC)
            assertNotNull(live.readings.intakeC)
            assertNotNull(live.readings.loadPct)
            assertNotNull(live.readings.fuelPct)
            assertNotNull(live.readings.voltage)
            assertEquals(7, live.readings.okCount)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun codesAreReadOnRequest() {
        val monitor = TruckMonitor({ DemoTransport() }, pollIntervalMillis = 10)
        monitor.start()
        try {
            await { monitor.state.first { it is TruckState.Live } }
            assertEquals(CodesState.Idle, monitor.codes.value)
            monitor.requestCodes()
            val loaded = await { monitor.codes.first { it is CodesState.Loaded } } as CodesState.Loaded
            assertEquals(listOf(Dtc("P0420"), Dtc("P0171")), loaded.stored)
            assertEquals(true, loaded.status?.milOn)
            assertEquals(2, loaded.status?.dtcCount)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun missingAdapterFailsWithItsMessageAndDoesNotRetry() {
        val monitor = TruckMonitor({ throw AdapterUnavailableException("Bluetooth is off on the phone.") })
        monitor.start()
        try {
            val failed = await { monitor.state.first { it is TruckState.Failed } } as TruckState.Failed
            assertEquals("Bluetooth is off on the phone.", failed.message)
            assertEquals(1, monitor.connectAttempts)
            monitor.requestCodes()
            assertTrue(monitor.codes.value is CodesState.Failed)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun initFailureIsExplainedAndNotRetried() {
        val monitor = TruckMonitor({
            object : ObdTransport by DemoTransport() {
                override fun write(bytes: ByteArray) = throw IOException("broken pipe")
            }
        }, pollIntervalMillis = 10)
        monitor.start()
        try {
            val failed = await { monitor.state.first { it is TruckState.Failed } } as TruckState.Failed
            assertTrue(failed.message, failed.message.startsWith("Lost the adapter"))
            assertEquals(1, monitor.connectAttempts)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun aDroppedLinkIsReconnected() {
        var opens = 0
        val monitor = TruckMonitor({
            opens++
            val demo = DemoTransport()
            if (opens == 1) {
                // Works through init and a few polls, then the socket dies.
                object : ObdTransport by demo {
                    var writes = 0
                    override fun write(bytes: ByteArray) {
                        if (++writes > 20) throw IOException("link reset")
                        demo.write(bytes)
                    }
                }
            } else {
                demo
            }
        }, pollIntervalMillis = 10, reconnectDelayMillis = 10)
        monitor.start()
        try {
            await { monitor.state.first { it is TruckState.Connecting && it.retry == 1 } }
            await { monitor.state.first { it is TruckState.Live } }
            assertEquals(2, monitor.connectAttempts)
        } finally {
            monitor.close()
        }
    }

    @Test
    fun closeClosesTheTransport() {
        val demo = DemoTransport()
        val monitor = TruckMonitor({ demo }, pollIntervalMillis = 10)
        monitor.start()
        await { monitor.state.first { it is TruckState.Live } }
        monitor.close()
        assertEquals(-1, demo.read(ByteArray(4), 0))
    }
}
