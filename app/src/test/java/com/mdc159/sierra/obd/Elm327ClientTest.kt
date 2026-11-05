package com.mdc159.sierra.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ClientTest {

    /** A fake clock so timeout tests don't sleep. */
    private class Clock {
        var nowNanos = 0L
        fun advanceMillis(ms: Long) {
            nowNanos += ms * 1_000_000
        }
    }

    private fun client(
        transport: FakeTransport,
        clock: Clock = Clock(),
        config: Elm327Config = Elm327Config(),
    ) = Elm327Client(transport, config) { clock.nowNanos }

    @Test
    fun initializeSendsReadOnlySetupSequence() {
        val t = FakeTransport.initialized()
        val info = client(t).initialize()
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6", "0100", "ATDPN"), t.sent)
        assertEquals("ELM327 v1.5", info.identity)
        assertEquals("6", info.protocol)
    }

    @Test
    fun initializeFailsWhenSetupNotAcknowledged() {
        val t = FakeTransport.initialized().apply { on("ATE0", "?") }
        val e = assertThrows(ObdAdapterException::class.java) { client(t).initialize() }
        assertEquals("ATE0", e.command)
    }

    @Test
    fun readsRpm() {
        val t = FakeTransport.initialized().apply { on("010C", "410C0BB8") }
        val c = client(t)
        c.initialize()
        assertEquals(750.0, c.read(ObdPids.ENGINE_RPM), 0.001)
        assertEquals("010C", t.sent.last())
    }

    @Test
    fun readsPidWhenMultipleEcusAnswer() {
        val t = FakeTransport.initialized().apply { on("0105", "41058A\r41058C") }
        val c = client(t)
        c.initialize()
        assertEquals(98.0, c.read(ObdPids.COOLANT_TEMP), 0.001)
    }

    @Test
    fun noDataIsAnAdapterError() {
        val t = FakeTransport.initialized().apply { on("015C", "NO DATA") }
        val c = client(t)
        c.initialize()
        val e = assertThrows(ObdAdapterException::class.java) { c.read(ObdPids.ENGINE_OIL_TEMP) }
        assertEquals("NO DATA", e.reason)
    }

    @Test
    fun negativeResponseIsReported() {
        val t = FakeTransport.initialized().apply { on("221940", "7F2231") }
        val c = client(t)
        c.initialize()
        val e = assertThrows(ObdNegativeResponseException::class.java) { c.readExtended(GmExtendedPids.TRANS_FLUID_TEMP) }
        assertEquals(0x22, e.service)
        assertEquals(0x31, e.code)
    }

    @Test
    fun timeoutThrowsAndResyncsBeforeNextCommand() {
        val clock = Clock()
        val t = FakeTransport.initialized().apply {
            on("010C", "410C0BB8")
            silent += "010D"
        }
        // A read that returns nothing advances the fake clock by the slice it was asked to wait.
        val timingTransport = object : ObdTransport by t {
            override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
                val n = t.read(buffer, timeoutMillis)
                if (n == 0) clock.advanceMillis(maxOf(timeoutMillis, 1))
                return n
            }
        }
        val config = Elm327Config(commandTimeoutMillis = 500, resyncTimeoutMillis = 100)
        val c = Elm327Client(timingTransport, config) { clock.nowNanos }
        c.initialize()
        val e = assertThrows(ObdTimeoutException::class.java) { c.read(ObdPids.VEHICLE_SPEED) }
        assertEquals("010D", e.command)
        assertEquals(500, e.timeoutMillis)

        // The late reply arrives; the client must swallow it, not return it as the RPM answer.
        t.inject("410D32\r\r>")
        assertEquals(750.0, c.read(ObdPids.ENGINE_RPM), 0.001)
    }

    @Test
    fun disconnectIsReported() {
        val t = FakeTransport.initialized()
        val c = client(t)
        c.initialize()
        t.close()
        assertThrows(ObdDisconnectedException::class.java) { c.read(ObdPids.ENGINE_RPM) }
    }

    @Test
    fun readsStoredCodesAcrossEcus() {
        val t = FakeTransport.initialized().apply { on("03", "430204200171\r43010300") }
        val c = client(t)
        c.initialize()
        assertEquals(listOf(Dtc("P0420"), Dtc("P0171"), Dtc("P0300")), c.readStoredCodes())
    }

    @Test
    fun noDataForCodesMeansNoCodes() {
        val t = FakeTransport.initialized().apply {
            on("03", "NO DATA")
            on("07", "4700")
            on("0A", "4A00")
        }
        val c = client(t)
        c.initialize()
        assertEquals(emptyList<Dtc>(), c.readStoredCodes())
        assertEquals(emptyList<Dtc>(), c.readPendingCodes())
        assertEquals(emptyList<Dtc>(), c.readPermanentCodes())
    }

    @Test
    fun readsVinFromMultiFrame() {
        val t = FakeTransport.initialized().apply {
            on("0902", "014\r0:490201314743\r1:5431324533374A\r2:35393337313131")
        }
        val c = client(t)
        c.initialize()
        assertEquals("1GCT12E37J5937111", c.readVin())
    }

    @Test
    fun supportedPidsWalksRanges() {
        val t = FakeTransport.initialized().apply {
            on("0100", "4100BE3EA813")
            on("0120", "412080000001")
            on("0140", "414000000000")
        }
        val c = client(t)
        c.initialize()
        val pids = c.supportedPids()
        assertTrue(0x0C in pids)
        assertTrue(0x21 in pids)
        assertTrue(0x40 in pids)
        assertEquals(listOf("0100", "0120", "0140"), t.sent.takeLast(3))
    }

    @Test
    fun extendedPidSwitchesHeaderAndRestoresIt() {
        val t = FakeTransport.initialized().apply { on("221940", "62194085") }
        val c = client(t)
        c.initialize()
        assertEquals(93.0, c.readExtended(GmExtendedPids.TRANS_FLUID_TEMP), 0.001)
        assertEquals(listOf("ATSH7E2", "221940", "ATSH7DF"), t.sent.takeLast(3))
    }

    @Test
    fun clearCodesRefusedWithoutConfirmation() {
        val t = FakeTransport.initialized().apply { on("04", "44") }
        val c = client(t)
        c.initialize()
        assertThrows(ObdCommandBlockedException::class.java) {
            c.clearDiagnosticCodes(ClearCodesRequest(userConfirmed = false, vehicleStopped = true))
        }
        assertThrows(ObdCommandBlockedException::class.java) {
            c.clearDiagnosticCodes(ClearCodesRequest(userConfirmed = true, vehicleStopped = false))
        }
        assertTrue("04" !in t.sent)
    }

    @Test
    fun clearCodesRefusedWhileMoving() {
        val t = FakeTransport.initialized().apply {
            on("010D", "410D32")
            on("04", "44")
        }
        val c = client(t)
        c.initialize()
        assertThrows(ObdCommandBlockedException::class.java) {
            c.clearDiagnosticCodes(ClearCodesRequest(userConfirmed = true, vehicleStopped = true))
        }
        assertTrue("04" !in t.sent)
    }

    @Test
    fun clearCodesSendsMode04WhenStoppedAndConfirmed() {
        val t = FakeTransport.initialized().apply {
            on("010D", "410D00")
            on("04", "44")
        }
        val c = client(t)
        c.initialize()
        c.clearDiagnosticCodes(ClearCodesRequest(userConfirmed = true, vehicleStopped = true))
        assertEquals("04", t.sent.last())
    }

    @Test
    fun rawWriteServicesNeverReachTheWire() {
        val t = FakeTransport.initialized()
        val c = client(t)
        c.initialize()
        assertThrows(ObdCommandBlockedException::class.java) { c.query(0x10, byteArrayOf(0x03)) }
        assertThrows(ObdCommandBlockedException::class.java) { c.query(0x2E, byteArrayOf(0x19, 0x40, 0x00)) }
        assertTrue(t.sent.none { it.startsWith("10") || it.startsWith("2E") })
    }
}
