package com.mdc159.sierra.car

import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import com.mdc159.sierra.obd.Dtc
import com.mdc159.sierra.obd.MonitorStatus
import com.mdc159.sierra.obd.live.CodesState
import com.mdc159.sierra.obd.live.TruckReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builds every Truck, Codes and Home template state with the real Car App Library
 * builders, which throw on host-rule violations (row counts, text lines, action limits).
 */
class TruckTemplatesTest {

    private val full = TruckReadings(
        rpm = 1850.0, speedKmh = 104.6, coolantC = 90.0, intakeC = 30.0,
        loadPct = 61.6, fuelPct = 62.0, voltage = 14.12, okCount = 7,
    )

    @Test
    fun truckLoadingBuilds() {
        val t = TruckScreen.loadingTemplate("Truck") as PaneTemplate
        assertTrue(t.pane.isLoading)
    }

    @Test
    fun truckLiveHasFourFixedRowsAndOneAction() {
        for (readings in listOf(full, TruckReadings())) {
            val t = TruckScreen.liveTemplate("Truck (demo)", readings) {} as PaneTemplate
            val rows = t.pane.rows.map { it as Row }
            assertEquals(listOf("Engine", "Speed", "Temperature", "Fuel and battery"), rows.map { it.title.toString() })
            assertEquals(1, t.pane.actions.size)
            assertEquals("Codes", t.pane.actions[0].title.toString())
        }
        val live = TruckScreen.liveTemplate("Truck", full) {} as PaneTemplate
        val texts = live.pane.rows.map { (it as Row).texts.map { c -> c.toString() } }
        assertEquals(listOf("1,850 rpm", "Load 62%"), texts[0])
        assertEquals(listOf("65 mph"), texts[1])
        assertEquals(listOf("Coolant 194 °F", "Intake air 86 °F"), texts[2])
        assertEquals(listOf("Fuel 62%", "14.1 V"), texts[3])
    }

    @Test
    fun truckFailureAndPermissionAreMessages() {
        val failed = TruckScreen.failedTemplate("Truck", "Bluetooth is off on the phone.") {} as MessageTemplate
        assertEquals("Retry", failed.actions[0].title.toString())
        val perm = TruckScreen.permissionTemplate("Truck") {} as MessageTemplate
        assertEquals("Allow", perm.actions[0].title.toString())
        // A very long adapter message is cut to the two lines a message template shows.
        TruckScreen.failedTemplate("Truck", "x".repeat(500)) {}
    }

    @Test
    fun codesLoadingAndFailedBuild() {
        val loading = TruckCodesScreen.template(CodesState.Loading, 6) {} as ListTemplate
        assertTrue(loading.isLoading)
        val idle = TruckCodesScreen.template(CodesState.Idle, 6) {} as ListTemplate
        assertTrue(idle.isLoading)
        val failed = TruckCodesScreen.template(CodesState.Failed("Lost the adapter."), 6) {} as ListTemplate
        assertEquals(1, failed.singleList!!.items.size)
        assertEquals("Retry", failed.actionStrip!!.actions[0].title.toString())
    }

    @Test
    fun codesListStaysWithinTheHostLimit() {
        val status = MonitorStatus(milOn = true, dtcCount = 2)
        val two = TruckCodesScreen.template(
            CodesState.Loaded(listOf(Dtc("P0420"), Dtc("P0171")), status), 6,
        ) {} as ListTemplate
        val titles = two.singleList!!.items.map { (it as Row).title.toString() }
        assertEquals(listOf("Check engine light is on", "P0420", "P0171", "Read-only"), titles)

        val many = (1..9).map { Dtc("P0%03d".format(it)) }
        for (limit in listOf(2, 3, 4, 6)) {
            val t = TruckCodesScreen.template(CodesState.Loaded(many, status), limit) {} as ListTemplate
            val rows = t.singleList!!.items.map { (it as Row).title.toString() }
            assertTrue("limit $limit gave ${rows.size} rows", rows.size <= limit)
            assertEquals("Read-only", rows.last())
            assertTrue(rows.any { it.startsWith("+") })
        }

        val none = TruckCodesScreen.template(CodesState.Loaded(emptyList(), null), 6) {} as ListTemplate
        assertEquals(listOf("No stored codes", "Read-only"), none.singleList!!.items.map { (it as Row).title.toString() })
    }

    @Test
    fun homeGridNeverExceedsTheHostLimit() {
        val entries = HomeScreen.entries(
            configured = false, demoTruck = false, {}, {}, {}, {},
            CarIcon.APP_ICON, CarIcon.BACK, CarIcon.ALERT, CarIcon.ERROR,
        )
        assertEquals(listOf("Talk", "Places", "Truck", "History"), entries.map { it.title })
        assertEquals("Set up on the phone first", entries[0].text)
        assertEquals("Live data", entries[2].text)
        val t = HomeScreen.template(entries, limit = 3) as GridTemplate
        assertEquals(3, t.singleList!!.items.size)
    }
}
