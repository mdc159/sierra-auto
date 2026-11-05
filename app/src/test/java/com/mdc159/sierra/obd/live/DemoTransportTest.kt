package com.mdc159.sierra.obd.live

import com.mdc159.sierra.obd.Dtc
import com.mdc159.sierra.obd.Elm327Client
import com.mdc159.sierra.obd.ObdCommandBlockedException
import com.mdc159.sierra.obd.ObdNegativeResponseException
import com.mdc159.sierra.obd.ObdPids
import com.mdc159.sierra.obd.GmExtendedPids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTransportTest {

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(seconds: Double) {
            now += (seconds * 1000).toLong()
        }
    }

    private fun connected(clock: FakeClock = FakeClock(), offset: Double = 0.0): Pair<Elm327Client, DemoTransport> {
        val t = DemoTransport({ clock.now }, offset)
        val c = Elm327Client(t)
        c.initialize()
        return c to t
    }

    @Test
    fun initializesLikeARealAdapter() {
        val t = DemoTransport()
        val info = Elm327Client(t).initialize()
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6", "0100", "ATDPN"), t.sent)
        assertEquals(DemoTransport.BANNER, info.identity)
        assertEquals("6", info.protocol)
    }

    @Test
    fun echoSpacesAndLinefeedsAreOnUntilTurnedOff() {
        // Before ATE0 the chip echoes; the client must still find the banner.
        val t = DemoTransport()
        t.write("ATZ\r".toByteArray())
        val buf = ByteArray(256)
        val n = t.read(buf, 0)
        val raw = String(buf, 0, n, Charsets.US_ASCII)
        assertTrue(raw, raw.startsWith("ATZ\r\n"))
        assertTrue(raw, raw.endsWith(">"))
    }

    @Test
    fun liveValuesArePlausibleAtIdle() {
        val (c, _) = connected()
        val rpm = c.read(ObdPids.ENGINE_RPM)
        assertTrue("idle rpm $rpm", rpm in 650.0..900.0)
        assertEquals(0.0, c.read(ObdPids.VEHICLE_SPEED), 0.001)
        assertTrue(c.read(ObdPids.COOLANT_TEMP) in 55.0..100.0)
        assertTrue(c.read(ObdPids.INTAKE_AIR_TEMP) in 15.0..40.0)
        assertTrue(c.read(ObdPids.ENGINE_LOAD) in 5.0..40.0)
        assertTrue(c.read(ObdPids.FUEL_LEVEL) in 50.0..70.0)
        assertTrue(c.read(ObdPids.CONTROL_MODULE_VOLTAGE) in 13.5..14.5)
        assertTrue(c.readBatteryVoltage() in 13.5..14.5)
    }

    @Test
    fun valuesChangeAsTheDriveCycleAdvances() {
        val clock = FakeClock()
        val (c, _) = connected(clock)
        val idleRpm = c.read(ObdPids.ENGINE_RPM)
        clock.advance(45.0) // cruising
        val cruiseRpm = c.read(ObdPids.ENGINE_RPM)
        val cruiseSpeed = c.read(ObdPids.VEHICLE_SPEED)
        assertTrue("cruise rpm $cruiseRpm", cruiseRpm > idleRpm + 800)
        assertTrue("cruise speed $cruiseSpeed km/h", cruiseSpeed in 95.0..115.0)
        clock.advance(45.0) // back to idle, 90 s cycle
        assertEquals(0.0, c.read(ObdPids.VEHICLE_SPEED), 0.001)
        assertNotEquals(cruiseRpm, c.read(ObdPids.ENGINE_RPM), 1.0)
    }

    @Test
    fun coolantWarmsUp() {
        val cold = DemoEngine().snapshot(0.0).coolantC
        val warm = DemoEngine().snapshot(600.0).coolantC
        assertTrue(cold < 66.0)
        assertTrue(warm in 89.0..95.0)
    }

    @Test
    fun supportedPidsIncludeEverythingTheTruckScreenNeeds() {
        val (c, _) = connected()
        val pids = c.supportedPids()
        for (pid in listOf(0x01, 0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x2F, 0x42, 0x5C)) {
            assertTrue("PID %02X".format(pid), pid in pids)
        }
        assertTrue(0x60 !in pids)
    }

    @Test
    fun twoStoredCodesNothingPending() {
        val (c, _) = connected()
        assertEquals(DemoTransport.DEMO_CODES.map { Dtc(it) }, c.readStoredCodes())
        assertEquals(emptyList<Dtc>(), c.readPendingCodes())
        assertEquals(emptyList<Dtc>(), c.readPermanentCodes())
        val status = c.read(ObdPids.MONITOR_STATUS)
        assertTrue(status.milOn)
        assertEquals(2, status.dtcCount)
    }

    @Test
    fun vinComesBackAsMultiFrame() {
        val (c, _) = connected()
        assertEquals(DemoTransport.VIN, c.readVin())
    }

    @Test
    fun gmExtendedPidsAreNotSupportedByTheDemoTcm() {
        val (c, _) = connected()
        assertThrows(ObdNegativeResponseException::class.java) { c.readExtended(GmExtendedPids.TRANS_FLUID_TEMP) }
    }

    @Test
    fun clearingCodesIsStillBlockedThroughTheDemo() {
        val (c, t) = connected()
        assertThrows(ObdCommandBlockedException::class.java) { c.query(0x04, ByteArray(0)) }
        assertTrue("04" !in t.sent)
    }

    @Test
    fun closedTransportReportsEndOfStream() {
        val t = DemoTransport()
        t.close()
        assertEquals(-1, t.read(ByteArray(8), 0))
    }
}
