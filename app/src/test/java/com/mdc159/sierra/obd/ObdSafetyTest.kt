package com.mdc159.sierra.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ObdSafetyTest {
    @Test
    fun setupAtCommandsPass() {
        for (c in listOf("ATZ", "AT Z", "atz", "ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP6", "ATSP0", "ATST32", "ATDPN", "ATRV", "ATI", "ATCAF1")) {
            assertNull("$c should pass", ObdSafety.blockReason(c))
        }
    }

    @Test
    fun headersAreRestrictedToKnownModules() {
        assertNull(ObdSafety.blockReason("ATSH7DF"))
        assertNull(ObdSafety.blockReason("AT SH 7E2"))
        assertNotNull(ObdSafety.blockReason("ATSH7E8"))
        assertNotNull(ObdSafety.blockReason("ATSH241"))
    }

    @Test
    fun dangerousAtCommandsAreBlocked() {
        for (c in listOf("ATPP0CSV23", "ATPPFFON", "ATSD00", "ATMA", "ATCRA7E8", "ATFCSD300000", "ATBD", "ATRTR", "ATCV1250", "ATPB", "ATWM")) {
            assertNotNull("$c should be blocked", ObdSafety.blockReason(c))
        }
    }

    @Test
    fun readServicesPass() {
        for (c in listOf("0100", "010C", "01 0C 0D 05", "03", "07", "0A", "0902", "221940", "22 11 5C")) {
            assertNull("$c should pass", ObdSafety.blockReason(c))
        }
    }

    @Test
    fun writeServicesAreBlocked() {
        for (c in listOf("10 03", "1003", "27 01", "2E 1940 00", "31 01 FF 00", "3E", "28 03", "85", "14 FF FF FF", "AE", "2F")) {
            assertNotNull("$c should be blocked", ObdSafety.blockReason(c))
        }
    }

    @Test
    fun malformedRequestsAreBlocked() {
        assertNotNull(ObdSafety.blockReason(""))
        assertNotNull(ObdSafety.blockReason("01"))
        assertNotNull(ObdSafety.blockReason("010"))
        assertNotNull(ObdSafety.blockReason("01ZZ"))
        assertNotNull(ObdSafety.blockReason("22 19"))
        assertNotNull(ObdSafety.blockReason("03 00"))
        assertNotNull(ObdSafety.blockReason("01 01 02 03 04 05 06 07"))
    }

    @Test
    fun clearCodesNeedsOptIn() {
        assertNotNull(ObdSafety.blockReason("04"))
        assertNull(ObdSafety.blockReason("04", allowClearCodes = true))
        assertNotNull(ObdSafety.blockReason("04 00", allowClearCodes = true))
        val e = assertThrows(ObdCommandBlockedException::class.java) { ObdSafety.requireAllowed("04") }
        assertEquals("04", e.command)
    }
}
