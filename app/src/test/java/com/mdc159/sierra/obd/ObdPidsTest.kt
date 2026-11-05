package com.mdc159.sierra.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdPidsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun rpmIsQuarterOfAB() {
        assertEquals(750.0, ObdPids.ENGINE_RPM.decode(bytes(0x0B, 0xB8)), 0.001)
    }

    @Test
    fun coolantTempOffsetsBy40() {
        assertEquals(90.0, ObdPids.COOLANT_TEMP.decode(bytes(0x82)), 0.001)
        assertEquals(-40.0, ObdPids.COOLANT_TEMP.decode(bytes(0x00)), 0.001)
    }

    @Test
    fun speedIsRawKmh() {
        assertEquals(100.0, ObdPids.VEHICLE_SPEED.decode(bytes(0x64)), 0.001)
    }

    @Test
    fun percentPidsScaleTo255() {
        assertEquals(100.0, ObdPids.THROTTLE_POSITION.decode(bytes(0xFF)), 0.001)
        assertEquals(50.0, ObdPids.FUEL_LEVEL.decode(bytes(0x80)), 0.5)
    }

    @Test
    fun fuelTrimIsSignedPercent() {
        assertEquals(0.0, ObdPids.LONG_FUEL_TRIM_B1.decode(bytes(0x80)), 0.001)
        assertEquals(-100.0, ObdPids.LONG_FUEL_TRIM_B1.decode(bytes(0x00)), 0.001)
    }

    @Test
    fun controlModuleVoltageIsMillivolts() {
        assertEquals(14.2, ObdPids.CONTROL_MODULE_VOLTAGE.decode(bytes(0x37, 0x78)), 0.001)
    }

    @Test
    fun mafIsHundredths() {
        assertEquals(5.12, ObdPids.MAF_RATE.decode(bytes(0x02, 0x00)), 0.001)
    }

    @Test
    fun monitorStatusSplitsMilAndCount() {
        val on = ObdPids.MONITOR_STATUS.decode(bytes(0x83, 0x07, 0xE5, 0x00))
        assertTrue(on.milOn)
        assertEquals(3, on.dtcCount)
        val off = ObdPids.MONITOR_STATUS.decode(bytes(0x00, 0x07, 0xE5, 0x00))
        assertFalse(off.milOn)
        assertEquals(0, off.dtcCount)
    }

    @Test
    fun extraBytesAreIgnoredButShortRepliesFail() {
        assertEquals(90.0, ObdPids.COOLANT_TEMP.decode(bytes(0x82, 0xFF)), 0.001)
        assertThrows(ObdParseException::class.java) { ObdPids.ENGINE_RPM.decode(bytes(0x0B)) }
    }

    @Test
    fun supportedBitmapDecodes() {
        // BE 3E A8 13 is a classic textbook example.
        val supported = ObdPids.decodeSupported(0x00, bytes(0xBE, 0x3E, 0xA8, 0x13))
        assertEquals(setOf(1, 3, 4, 5, 6, 7, 11, 12, 13, 14, 15, 17, 19, 21, 28, 31, 32), supported)
        assertTrue(0x20 in supported)
    }

    @Test
    fun supportedBitmapWithBaseOffsets() {
        val supported = ObdPids.decodeSupported(0x20, bytes(0x80, 0x00, 0x00, 0x01))
        assertEquals(setOf(0x21, 0x40), supported)
    }

    @Test
    fun allTableHasUniquePids() {
        assertEquals(ObdPids.ALL.size, ObdPids.ALL.values.map { it.pid }.toSet().size)
        assertEquals(ObdPids.ENGINE_RPM, ObdPids.ALL[0x0C])
    }

    @Test
    fun gmExtendedPidsAreMarkedUnverified() {
        GmExtendedPids.ALL.forEach { assertFalse("${it.name} must stay unverified until checked", it.verified) }
        assertEquals(93.0, GmExtendedPids.TRANS_FLUID_TEMP.decode(bytes(0x85)), 0.001)
        assertEquals("7E2", GmExtendedPids.TRANS_FLUID_TEMP.header)
    }
}
