package com.mdc159.sierra.car

import androidx.car.app.model.CarIcon
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The Car App Library validates templates in their builders and throws on violations.
 * Building the real templates here catches those crashes before they reach the truck.
 */
class TemplateConstraintsTest {

    @Test
    fun homeTemplateBuilds() {
        for (configured in listOf(true, false)) {
            val t = HomeScreen.template(configured, {}, {}, {}, CarIcon.APP_ICON, CarIcon.BACK) as PaneTemplate
            assertEquals(2, t.actionStrip!!.actions.size)
            assertEquals(2, t.pane.actions.size)
        }
    }

    /** Proves these tests can see constraint violations: this is the M1 home strip. */
    @Test(expected = IllegalArgumentException::class)
    fun twoTitledStripActionsOnPaneAreRejected() {
        val pane = androidx.car.app.model.Pane.Builder()
            .addRow(androidx.car.app.model.Row.Builder().setTitle("x").build())
            .build()
        val strip = androidx.car.app.model.ActionStrip.Builder()
            .addAction(androidx.car.app.model.Action.Builder().setTitle("Places").setOnClickListener {}.build())
            .addAction(androidx.car.app.model.Action.Builder().setTitle("Talk").setOnClickListener {}.build())
            .build()
        PaneTemplate.Builder(pane).setTitle("Sierra").setActionStrip(strip).build()
    }

    // PlaceListMapTemplate.setItemList() inspects text spans, which needs a real
    // android.text implementation (Robolectric or a device), so its row rules are not
    // exercised here. Its rule, from ModelUtils bytecode: the number of non-browsable rows
    // must not exceed the number of rows carrying a DistanceSpan; status rows are browsable.

    @Test
    fun placeLoadingTemplateBuilds() {
        for (hostRefresh in listOf(false, true)) {
            val t = PlaceListScreen.baseBuilder("Fuel", onRefresh = {}, hostRefresh = hostRefresh)
                .setLoading(true)
                .build()
            assertNotNull(t)
        }
    }
}
