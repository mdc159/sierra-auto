package com.mdc159.sierra.obd.gm

import com.mdc159.sierra.obd.ObdParseException
import com.mdc159.sierra.obd.ObdSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GmDidsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun transFluidTempIsAMinus40() {
        assertEquals(93.0, GmDids.TRANS_FLUID_TEMP.decode(bytes(0x85)), 0.001)
        assertEquals(-40.0, GmDids.TRANS_FLUID_TEMP.decode(bytes(0x00)), 0.001)
        assertEquals(215.0, GmDids.TRANS_FLUID_TEMP.decode(bytes(0xFF)), 0.001)
    }

    @Test
    fun engineOilTempIsAMinus40() {
        assertEquals(100.0, GmDids.ENGINE_OIL_TEMP.decode(bytes(0x8C)), 0.001)
        assertEquals(-40.0, GmDids.ENGINE_OIL_TEMP.decode(bytes(0x00)), 0.001)
    }

    @Test
    fun oilPressureIsFourKpaPerCount() {
        assertEquals(0.0, GmDids.ENGINE_OIL_PRESSURE.decode(bytes(0x00)), 0.001)
        assertEquals(100.0, GmDids.ENGINE_OIL_PRESSURE.decode(bytes(0x19)), 0.001)
        assertEquals(1020.0, GmDids.ENGINE_OIL_PRESSURE.decode(bytes(0xFF)), 0.001)
        // 0.578 psi per count, as Torque's GM set puts it, is the same scale.
        assertEquals(0.58, 4.0 / 6.895, 0.003)
    }

    @Test
    fun oilPressureSensorIsFourKpaAbsolute() {
        assertEquals(204.0, GmDids.OIL_PRESSURE_SENSOR.decode(bytes(0x33)), 0.001)
        assertEquals(Confidence.SINGLE_SOURCE, GmDids.OIL_PRESSURE_SENSOR.formulaConfidence)
    }

    @Test
    fun oilLifeIsPercentOf255() {
        assertEquals(100.0, GmDids.OIL_LIFE.decode(bytes(0xFF)), 0.001)
        assertEquals(0.0, GmDids.OIL_LIFE.decode(bytes(0x00)), 0.001)
        assertEquals(50.196, GmDids.OIL_LIFE.decode(bytes(0x80)), 0.001)
    }

    @Test
    fun currentGearIsRawByte() {
        assertEquals(4, GmDids.CURRENT_GEAR.decode(bytes(0x04)))
        assertEquals(8, GmDids.CURRENT_GEAR.decode(bytes(0x08)))
    }

    @Test
    fun extraBytesAreIgnored() {
        assertEquals(93.0, GmDids.TRANS_FLUID_TEMP.decode(bytes(0x85, 0xAA, 0xBB)), 0.001)
    }

    @Test
    fun shortFramesThrowForEveryDid() {
        for (did in GmDids.ALL) {
            val e = assertThrows("$did", ObdParseException::class.java) { did.decode(ByteArray(0)) }
            assertTrue(e.message!!.contains("needs ${did.byteCount} bytes"))
        }
    }

    @Test
    fun requestStringsAreModeTwentyTwoPlusDid() {
        assertEquals("221940", GmDids.TRANS_FLUID_TEMP.request)
        assertEquals("221154", GmDids.ENGINE_OIL_TEMP.request)
        assertEquals("221470", GmDids.ENGINE_OIL_PRESSURE.request)
        assertEquals("22115C", GmDids.OIL_PRESSURE_SENSOR.request)
        assertEquals("22119F", GmDids.OIL_LIFE.request)
        assertEquals("22199A", GmDids.CURRENT_GEAR.request)
        assertEquals(0x19, GmDids.TRANS_FLUID_TEMP.didHigh)
        assertEquals(0x40, GmDids.TRANS_FLUID_TEMP.didLow)
    }

    @Test
    fun everyRequestAndHeaderPassesTheReadOnlyAllowlistUnchanged() {
        for (did in GmDids.ALL) {
            assertNull("${did.request} should pass", ObdSafety.blockReason(did.request))
            for (module in listOf(did.module) + did.fallbackModules) {
                assertNull("ATSH${module.requestHeader} should pass", ObdSafety.blockReason("ATSH${module.requestHeader}"))
            }
        }
        for (module in GmModule.entries) {
            assertTrue(module.requestHeader in ObdSafety.ALLOWED_HEADERS)
        }
    }

    @Test
    fun scanToolTrailingZeroOneVariantIsBlockedAndNotNeeded() {
        // ScanGauge and some Torque files append "01" (22194001). ObdSafety only allows
        // one 2-byte DID, and the plain form is what the predefined Torque set sends.
        assertNotNull(ObdSafety.blockReason("22194001"))
        assertNull(ObdSafety.blockReason("221940"))
    }

    @Test
    fun allDidsAreUniqueAndBackedByTwoOrMoreSources() {
        assertEquals(GmDids.ALL.size, GmDids.ALL.map { it.did }.toSet().size)
        for (did in GmDids.ALL) {
            assertEquals("$did", Confidence.CONFIRMED, did.didConfidence)
            assertTrue("$did needs at least two sources", did.sources.size >= 2)
        }
    }

    @Test
    fun modulesAndFallbacksAreSensible() {
        assertEquals(GmModule.TCM, GmDids.TRANS_FLUID_TEMP.module)
        assertEquals(listOf(GmModule.ECM, GmModule.TCM_ALT), GmDids.TRANS_FLUID_TEMP.fallbackModules)
        assertEquals(GmModule.ECM, GmDids.ENGINE_OIL_PRESSURE.module)
        assertEquals("7EA", GmModule.TCM.responseId)
        assertEquals(GmModule.TCM, GmModule.forHeader("7e2"))
        assertNull(GmModule.forHeader("7DF"))
    }

    @Test
    fun extendedPidBridgeStaysUnverifiedAndKeepsFormula() {
        val ext = GmDids.TRANS_FLUID_TEMP.toExtendedPid()
        assertFalse(ext.verified)
        assertEquals("7E2", ext.header)
        assertEquals(0x1940, ext.did)
        assertEquals(93.0, ext.decode(bytes(0x85)), 0.001)
        assertEquals("7E0", GmDids.TRANS_FLUID_TEMP.toExtendedPid(GmModule.ECM).header)
    }

    @Test
    fun pollingListsPartitionTheTable() {
        assertTrue(GmDids.OIL_LIFE in GmDids.ONCE_PER_TRIP)
        assertFalse(GmDids.OIL_LIFE in GmDids.LIVE)
        assertTrue(GmDids.TRANS_FLUID_TEMP in GmDids.LIVE)
        assertEquals(GmDids.ALL.size, GmDids.LIVE.size + GmDids.ONCE_PER_TRIP.size)
        assertEquals(3, GmDids.FIRST_TEST.size)
        assertEquals(GmDids.TRANS_FLUID_TEMP, GmDids.FIRST_TEST.first())
        assertEquals(GmDids.ENGINE_OIL_PRESSURE, GmDids.byDid(0x1470))
        assertNull(GmDids.byDid(0x0000))
    }

    @Test
    fun constructorRejectsBadDefinitions() {
        assertThrows(IllegalArgumentException::class.java) {
            GmDid(0x10000, GmModule.ECM, name = "x", unit = "", byteCount = 1,
                didConfidence = Confidence.GUESS, formulaConfidence = Confidence.GUESS,
                pollIntervalMillis = null, sources = emptyList()) { it[0] }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmDid(0x1940, GmModule.TCM, fallbackModules = listOf(GmModule.TCM), name = "x", unit = "", byteCount = 1,
                didConfidence = Confidence.GUESS, formulaConfidence = Confidence.GUESS,
                pollIntervalMillis = null, sources = emptyList()) { it[0] }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GmDid(0x1940, GmModule.TCM, name = "x", unit = "", byteCount = 0,
                didConfidence = Confidence.GUESS, formulaConfidence = Confidence.GUESS,
                pollIntervalMillis = null, sources = emptyList()) { it[0] }
        }
    }
}
