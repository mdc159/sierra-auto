package com.mdc159.sierra.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves that the host rules which crashed early builds on the truck now fail here.
 *
 * The Car App Library validates templates in their builders. Two of those checks
 * (PlaceListMapTemplate row rules and anything that inspects text spans) need a real
 * android.text implementation, so this class runs under Robolectric. The plain-JVM
 * checks live in [TemplateConstraintsTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HostRulesTest {

    // Crash 1 (M1 home screen): two titled buttons in a PaneTemplate action strip.

    @Test
    fun twoTitledActionsInPaneStripAreRejected() {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Places").setOnClickListener {}.build())
            .addAction(Action.Builder().setTitle("Talk").setOnClickListener {}.build())
            .build()
        val e = assertThrows(IllegalArgumentException::class.java) {
            PaneTemplate.Builder(pane()).setTitle("Sierra").setActionStrip(strip).build()
        }
        assertEquals("Action list exceeded max number of 1 actions with custom titles", e.message)
    }

    @Test
    fun oneTitledPlusOneIconActionInPaneStripBuilds() {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(CarIcon.APP_ICON).setOnClickListener {}.build())
            .addAction(Action.Builder().setTitle("History").setOnClickListener {}.build())
            .build()
        assertNotNull(PaneTemplate.Builder(pane()).setTitle("Sierra").setActionStrip(strip).build())
    }

    // Crash 2 (M2 places list): a non-browsable map-list row without a DistanceSpan.

    @Test
    fun mapListRowWithoutDistanceIsRejected() {
        val row = Row.Builder().setTitle("Shell").addText("12 Main St").setOnClickListener {}.build()
        val e = assertThrows(IllegalArgumentException::class.java) {
            PlaceListMapTemplate.Builder()
                .setTitle("Fuel")
                .setItemList(ItemList.Builder().addItem(row).build())
                .build()
        }
        assertEquals(
            "All non-browsable rows must have a distance span attached to either its title or texts",
            e.message,
        )
    }

    @Test
    fun mapListRowWithDistanceSpanBuilds() {
        val row = Row.Builder()
            .setTitle("Shell")
            .addText(distanceText(1.2))
            .addText("12 Main St")
            .setOnClickListener {}
            .build()
        val template = PlaceListMapTemplate.Builder()
            .setTitle("Fuel")
            .setItemList(ItemList.Builder().addItem(row).build())
            .build()
        assertEquals(1, template.itemList!!.items.size)
    }

    @Test
    fun browsableStatusRowNeedsNoDistance() {
        // This is why PlaceListScreen's error and permission rows are browsable.
        val row = Row.Builder().setTitle("Couldn't load places. Tap to retry.")
            .addText("Timed out.").setBrowsable(true).setOnClickListener {}.build()
        assertNotNull(
            PlaceListMapTemplate.Builder().setTitle("Fuel")
                .setItemList(ItemList.Builder().addItem(row).build()).build()
        )
    }

    @Test
    fun nonBrowsableStatusRowWouldCrash() {
        val row = Row.Builder().setTitle("Couldn't load places. Tap to retry.")
            .addText("Timed out.").setOnClickListener {}.build()
        assertThrows(IllegalArgumentException::class.java) {
            PlaceListMapTemplate.Builder().setTitle("Fuel")
                .setItemList(ItemList.Builder().addItem(row).build()).build()
        }
    }

    // Other builder-time rules worth a regression guard.

    @Test
    fun mapListRowWithThreeTextLinesIsRejected() {
        val row = Row.Builder().setTitle("Shell").addText(distanceText(1.0)).addText("a").addText("b")
            .setOnClickListener {}.build()
        assertThrows(IllegalArgumentException::class.java) {
            PlaceListMapTemplate.Builder().setTitle("Fuel")
                .setItemList(ItemList.Builder().addItem(row).build()).build()
        }
    }

    @Test
    fun mapListLoadingWithListIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaceListMapTemplate.Builder().setTitle("Fuel").setLoading(true)
                .setItemList(ItemList.Builder().build()).build()
        }
    }

    @Test
    fun mapStripAllowsFourTitledActionsButNotFive() {
        fun strip(n: Int) = ActionStrip.Builder().apply {
            repeat(n) { addAction(Action.Builder().setTitle("A$it").setOnClickListener {}.build()) }
        }.build()
        assertNotNull(PlaceListMapTemplate.Builder().setTitle("Fuel").setLoading(true).setActionStrip(strip(4)).build())
        assertThrows(IllegalArgumentException::class.java) {
            PlaceListMapTemplate.Builder().setTitle("Fuel").setLoading(true).setActionStrip(strip(5)).build()
        }
    }

    @Test
    fun paneWithThreeActionsIsRejected() {
        val pane = Pane.Builder().addRow(Row.Builder().setTitle("x").build())
        repeat(3) { pane.addAction(Action.Builder().setTitle("A$it").setOnClickListener {}.build()) }
        assertThrows(IllegalArgumentException::class.java) {
            PaneTemplate.Builder(pane.build()).setTitle("Sierra").build()
        }
    }

    @Test
    fun paneWithTwoPrimaryActionsIsRejected() {
        val pane = Pane.Builder().addRow(Row.Builder().setTitle("x").build())
        repeat(2) {
            pane.addAction(
                Action.Builder().setTitle("A$it").setFlags(Action.FLAG_PRIMARY).setOnClickListener {}.build()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaneTemplate.Builder(pane.build()).setTitle("Sierra").build()
        }
    }

    @Test
    fun paneRowWithClickListenerIsRejected() {
        val pane = Pane.Builder().addRow(Row.Builder().setTitle("x").setOnClickListener {}.build()).build()
        assertThrows(IllegalArgumentException::class.java) {
            PaneTemplate.Builder(pane).setTitle("Sierra").build()
        }
    }

    @Test
    fun emptyRowTitleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Row.Builder().setTitle("") }
        assertThrows(IllegalStateException::class.java) { Row.Builder().build() }
    }

    @Test
    fun browsableRowWithoutClickListenerIsRejected() {
        assertThrows(IllegalStateException::class.java) { Row.Builder().setTitle("x").setBrowsable(true).build() }
    }

    @Test
    fun listTemplateLoadingWithListIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            ListTemplate.Builder().setTitle("History").setLoading(true)
                .setSingleList(ItemList.Builder().build()).build()
        }
    }

    @Test
    fun emptyMessageIsRejected() {
        assertThrows(IllegalStateException::class.java) { MessageTemplate.Builder("").setTitle("Sierra").build() }
    }

    @Test
    fun loadingMessageWithIconIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            MessageTemplate.Builder("Thinking…").setTitle("Sierra").setLoading(true).setIcon(CarIcon.APP_ICON).build()
        }
    }

    private fun pane(): Pane = Pane.Builder().addRow(Row.Builder().setTitle("x").build()).build()

    companion object {
        fun distanceText(miles: Double): CharSequence = SpannableString(" ").apply {
            setSpan(
                DistanceSpan.create(Distance.create(miles, Distance.UNIT_MILES)),
                0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
    }
}
