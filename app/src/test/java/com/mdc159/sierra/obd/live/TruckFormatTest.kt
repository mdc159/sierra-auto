package com.mdc159.sierra.obd.live

import org.junit.Assert.assertEquals
import org.junit.Test

class TruckFormatTest {
    @Test
    fun convertsToUsUnits() {
        assertEquals("1,850 rpm", TruckFormat.rpm(1850.0))
        assertEquals("65 mph", TruckFormat.mph(104.6))
        assertEquals("194 °F", TruckFormat.fahrenheit(90.0))
        assertEquals("62%", TruckFormat.percent(61.6))
        assertEquals("14.1 V", TruckFormat.volts(14.12))
    }

    @Test
    fun missingValuesReadNotAvailable() {
        assertEquals(TruckFormat.NA, TruckFormat.rpm(null))
        assertEquals(TruckFormat.NA, TruckFormat.mph(null))
        assertEquals(TruckFormat.NA, TruckFormat.fahrenheit(null))
        assertEquals(TruckFormat.NA, TruckFormat.percent(null))
        assertEquals(TruckFormat.NA, TruckFormat.volts(null))
    }

    @Test
    fun protocolNames() {
        assertEquals("6: ISO 15765-4 CAN (11-bit, 500 kbaud)", ObdProtocols.describe("6"))
        assertEquals("6: ISO 15765-4 CAN (11-bit, 500 kbaud), auto-detected", ObdProtocols.describe("A6"))
        assertEquals("unknown (Z)", ObdProtocols.describe("Z"))
        assertEquals("unknown", ObdProtocols.describe(""))
    }

    @Test
    fun settingsLabels() {
        assertEquals("Demo adapter", ObdSettings.DEMO.label)
        assertEquals("OBDII", ObdSettings(AdapterKind.CLASSIC, "00:11:22:33:44:55", "OBDII").label)
        assertEquals("00:11:22:33:44:55", ObdSettings(AdapterKind.CLASSIC, "00:11:22:33:44:55").label)
        assertEquals("Veepeak (LE)", ObdSettings(AdapterKind.BLE, "AA:BB", "Veepeak").label)
    }
}
