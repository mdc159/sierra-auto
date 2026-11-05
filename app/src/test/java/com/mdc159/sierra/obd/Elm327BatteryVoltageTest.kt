package com.mdc159.sierra.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Elm327BatteryVoltageTest {
    @Test
    fun readsAtrv() {
        val t = FakeTransport.initialized().apply { on("ATRV", "14.1V") }
        val c = Elm327Client(t)
        c.initialize()
        assertEquals(14.1, c.readBatteryVoltage(), 0.001)
        assertEquals("ATRV", t.sent.last())
    }

    @Test
    fun acceptsLowerCaseAndSpaces() {
        val t = FakeTransport.initialized().apply { on("ATRV", "12.6 v") }
        val c = Elm327Client(t)
        c.initialize()
        assertEquals(12.6, c.readBatteryVoltage(), 0.001)
    }

    @Test
    fun garbageIsAParseError() {
        val t = FakeTransport.initialized().apply { on("ATRV", "?") }
        val c = Elm327Client(t)
        c.initialize()
        assertThrows(ObdParseException::class.java) { c.readBatteryVoltage() }
    }
}
