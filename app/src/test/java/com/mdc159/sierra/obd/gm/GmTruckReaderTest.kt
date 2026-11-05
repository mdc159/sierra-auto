package com.mdc159.sierra.obd.gm

import com.mdc159.sierra.obd.Elm327Client
import com.mdc159.sierra.obd.Elm327Config
import com.mdc159.sierra.obd.FakeTransport
import com.mdc159.sierra.obd.ObdDisconnectedException
import com.mdc159.sierra.obd.ObdSafety
import com.mdc159.sierra.obd.ObdTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GmTruckReaderTest {

    /**
     * Routes a Mode 22 command to a different scripted reply depending on which
     * `AT SH` header is active, so a test can make the TCM and the ECM disagree.
     */
    private class HeaderAwareTransport(val fake: FakeTransport = FakeTransport.initialized()) : ObdTransport by fake {
        private var header = "7DF"
        private val byHeader = mutableMapOf<Pair<String, String>, String>()

        init {
            // FakeTransport.initialized() scripts 7DF, 7E0 and 7E2 only.
            fake.on("ATSH7E1", "OK")
        }

        fun on(header: String, command: String, reply: String) {
            byHeader[header to ObdSafety.normalize(command)] = reply
        }

        override fun write(bytes: ByteArray) {
            val command = ObdSafety.normalize(String(bytes, Charsets.US_ASCII).trimEnd('\r'))
            if (command.startsWith("ATSH")) header = command.removePrefix("ATSH")
            byHeader[header to command]?.let { fake.on(command, it) }
            fake.write(bytes)
        }
    }

    private fun reader(t: ObdTransport): GmTruckReader {
        val client = Elm327Client(t)
        client.initialize()
        return GmTruckReader(client)
    }

    @Test
    fun readsTransTempFromTcmAndRestoresBroadcastHeader() {
        val t = FakeTransport.initialized().apply { on("221940", "62194085") }
        val r = reader(t).read(GmDids.TRANS_FLUID_TEMP)
        assertEquals(GmReading.Value(93.0, GmModule.TCM), r)
        assertEquals(listOf("ATSH7E2", "221940", "ATSH7DF"), t.sent.takeLast(3))
    }

    @Test
    fun readsEcmDids() {
        val t = FakeTransport.initialized().apply {
            on("221470", "62147019")
            on("221154", "6211548C")
            on("22119F", "62119FFF")
            on("22199A", "62199A06")
        }
        val reader = reader(t)
        assertEquals(100.0, reader.read(GmDids.ENGINE_OIL_PRESSURE).valueOrNull()!!, 0.001)
        assertEquals(100.0, reader.read(GmDids.ENGINE_OIL_TEMP).valueOrNull()!!, 0.001)
        assertEquals(100.0, reader.read(GmDids.OIL_LIFE).valueOrNull()!!, 0.001)
        assertEquals(6, reader.read(GmDids.CURRENT_GEAR).valueOrNull())
        assertTrue("ATSH7E0" in t.sent)
    }

    @Test
    fun negativeResponseIsAReadingNotAnException() {
        val t = FakeTransport.initialized().apply { on("221940", "7F2231") }
        val r = reader(t).read(GmDids.TRANS_FLUID_TEMP)
        assertEquals(GmReading.NegativeResponse(0x31, GmModule.TCM), r)
        assertEquals("ATSH7DF", t.sent.last())
    }

    @Test
    fun noDataIsAReading() {
        val t = FakeTransport.initialized().apply { on("221940", "NO DATA") }
        assertEquals(GmReading.NoReply("NO DATA", GmModule.TCM), reader(t).read(GmDids.TRANS_FLUID_TEMP))
    }

    @Test
    fun shortFrameIsMalformed() {
        val t = FakeTransport.initialized().apply { on("221940", "6219") }
        val r = reader(t).read(GmDids.TRANS_FLUID_TEMP)
        assertTrue("$r", r is GmReading.Malformed)
    }

    @Test
    fun moduleOverrideChangesTheHeader() {
        val t = FakeTransport.initialized().apply { on("221940", "62194085") }
        val r = reader(t).read(GmDids.TRANS_FLUID_TEMP, GmModule.ECM)
        assertEquals(GmReading.Value(93.0, GmModule.ECM), r)
        assertEquals(listOf("ATSH7E0", "221940", "ATSH7DF"), t.sent.takeLast(3))
    }

    @Test
    fun fallbackMovesToEcmWhenTcmSaysOutOfRange() {
        val t = HeaderAwareTransport().apply {
            on("7E2", "221940", "7F2231")
            on("7E0", "221940", "62194085")
        }
        val r = reader(t).readWithFallbacks(GmDids.TRANS_FLUID_TEMP)
        assertEquals(GmReading.Value(93.0, GmModule.ECM), r)
        assertEquals(listOf("ATSH7E2", "221940", "ATSH7DF", "ATSH7E0", "221940", "ATSH7DF"), t.fake.sent.takeLast(6))
    }

    @Test
    fun fallbackMovesOnAfterNoData() {
        val t = HeaderAwareTransport().apply {
            on("7E2", "221940", "NO DATA")
            on("7E0", "221940", "NO DATA")
            on("7E1", "221940", "62194085")
        }
        val r = reader(t).readWithFallbacks(GmDids.TRANS_FLUID_TEMP)
        assertEquals(GmReading.Value(93.0, GmModule.TCM_ALT), r)
    }

    @Test
    fun fallbackStopsWhenTheModuleKnowsTheDidButRefuses() {
        val t = HeaderAwareTransport().apply {
            on("7E2", "221940", "7F2222")
            on("7E0", "221940", "62194085")
        }
        val r = reader(t).readWithFallbacks(GmDids.TRANS_FLUID_TEMP)
        assertEquals(GmReading.NegativeResponse(0x22, GmModule.TCM), r)
        assertEquals(1, t.fake.sent.count { it == "221940" })
    }

    @Test
    fun fallbackReturnsLastAnswerWhenNothingWorks() {
        val t = HeaderAwareTransport().apply {
            on("7E2", "221940", "7F2231")
            on("7E0", "221940", "NO DATA")
            on("7E1", "221940", "7F2211")
        }
        val r = reader(t).readWithFallbacks(GmDids.TRANS_FLUID_TEMP)
        assertEquals(GmReading.NegativeResponse(0x11, GmModule.TCM_ALT), r)
    }

    @Test
    fun didWithoutFallbacksIsAskedOnce() {
        val t = FakeTransport.initialized().apply { on("221154", "NO DATA") }
        val r = reader(t).readWithFallbacks(GmDids.ENGINE_OIL_TEMP)
        assertEquals(GmReading.NoReply("NO DATA", GmModule.ECM), r)
        assertEquals(1, t.sent.count { it == "221154" })
    }

    @Test
    fun timeoutIsNoReplyAndTheNextReadStillWorks() {
        var nowNanos = 0L
        val fake = FakeTransport.initialized().apply {
            on("221470", "62147019")
            silent += "221940"
        }
        val timing = object : ObdTransport by fake {
            override fun read(buffer: ByteArray, timeoutMillis: Long): Int {
                val n = fake.read(buffer, timeoutMillis)
                if (n == 0) nowNanos += maxOf(timeoutMillis, 1) * 1_000_000
                return n
            }
        }
        val client = Elm327Client(timing, Elm327Config(commandTimeoutMillis = 500, resyncTimeoutMillis = 100)) { nowNanos }
        client.initialize()
        val reader = GmTruckReader(client)
        val r = reader.read(GmDids.TRANS_FLUID_TEMP)
        assertEquals(GmReading.NoReply("timeout after 500 ms", GmModule.TCM), r)
        assertEquals(100.0, reader.read(GmDids.ENGINE_OIL_PRESSURE).valueOrNull()!!, 0.001)
    }

    @Test
    fun disconnectStillThrows() {
        val t = FakeTransport.initialized()
        val reader = reader(t)
        t.close()
        assertThrows(ObdDisconnectedException::class.java) { reader.read(GmDids.TRANS_FLUID_TEMP) }
    }

    @Test
    fun firstTestReadsTheThreeDidsAndSendsOnlyReads() {
        val t = FakeTransport.initialized().apply {
            on("221940", "62194085")
            on("221470", "62147019")
            on("221154", "6211548C")
        }
        val readings = reader(t).firstTest()
        assertEquals(3, readings.size)
        assertTrue(readings.all { it.isValue })
        val afterInit = t.sent.drop(9) // ATZ ... ATDPN
        val allowed = Regex("ATSH7E[012]|ATSH7DF|22[0-9A-F]{4}")
        assertTrue(afterInit.toString(), afterInit.all { allowed.matches(it) })
    }
}
