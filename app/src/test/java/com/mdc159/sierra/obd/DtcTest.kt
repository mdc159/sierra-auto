package com.mdc159.sierra.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun decodesEachLetter() {
        assertEquals("P0420", Dtc.fromBytes(0x04, 0x20)?.code)
        assertEquals("P1234", Dtc.fromBytes(0x12, 0x34)?.code)
        assertEquals("C0035", Dtc.fromBytes(0x40, 0x35)?.code)
        assertEquals("B1000", Dtc.fromBytes(0x90, 0x00)?.code)
        assertEquals("U0100", Dtc.fromBytes(0xC1, 0x00)?.code)
        assertEquals("P2A00", Dtc.fromBytes(0x2A, 0x00)?.code)
    }

    @Test
    fun zeroIsFiller() {
        assertNull(Dtc.fromBytes(0, 0))
    }

    @Test
    fun parsesCanPayloadWithCountByte() {
        // 43 02 04 20 01 71 -> count=2, P0420, P0171
        assertEquals(listOf(Dtc("P0420"), Dtc("P0171")), Dtc.parseList(bytes(0x02, 0x04, 0x20, 0x01, 0x71)))
    }

    @Test
    fun parsesLegacyPayloadAndDropsFiller() {
        assertEquals(listOf(Dtc("P0300")), Dtc.parseList(bytes(0x03, 0x00, 0x00, 0x00, 0x00, 0x00)))
    }

    @Test
    fun emptyCanPayloadYieldsNothing() {
        assertEquals(emptyList<Dtc>(), Dtc.parseList(bytes(0x00)))
    }
}
