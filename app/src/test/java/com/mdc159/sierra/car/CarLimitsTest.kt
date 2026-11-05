package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.testing.TestAppManager
import androidx.car.app.versioning.CarAppApiLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarLimitsTest {

    @Test
    fun apiLevel1HostGetsTheDocumentedDefaults() {
        val ctx = CarTestHost.carContext(apiLevel = CarAppApiLevels.LEVEL_1)
        assertEquals(6, CarLimits.listLimit(ctx))
        assertEquals(6, CarLimits.placeListLimit(ctx))
        assertEquals(6, CarLimits.listLimit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_GRID))
        assertEquals(3, CarLimits.listLimit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST))
        assertEquals(4, CarLimits.listLimit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_PANE))
    }

    @Test
    fun hostWithoutConstraintServiceFallsBackPerType() {
        // The test host does not implement the constraint service, like an old or broken host.
        val ctx = CarTestHost.carContext(apiLevel = CarAppApiLevels.LEVEL_5)
        assertEquals(6, CarLimits.listLimit(ctx))
        assertEquals(6, CarLimits.placeListLimit(ctx))
        assertEquals(4, CarLimits.listLimit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_PANE))
        assertEquals(3, CarLimits.listLimit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST))
    }

    @Test
    fun unknownTypeStillYieldsAUsableLimit() {
        val ctx = CarTestHost.carContext()
        assertTrue(CarLimits.listLimit(ctx, 99) >= 1)
    }

    @Test
    fun navHandoffUsesTheNavigateActionWithAGeoQuery() {
        val ctx = CarTestHost.carContext()
        NavHandoff.toPlace(ctx, 38.5, -121.5, "Joe's (Diner) & Grill")
        val intent = ctx.startCarAppIntents.single()
        assertEquals(CarContext.ACTION_NAVIGATE, intent.action)
        // Parentheses would end the geo label early, so they are stripped; the rest is encoded.
        assertEquals("geo:0,0?q=38.5,-121.5(Joe's%20%20Diner%20%20%26%20Grill)", intent.dataString)

        ctx.reset()
        NavHandoff.search(ctx, "rest area")
        assertEquals("geo:0,0?q=rest%20area", ctx.startCarAppIntents.single().dataString)
    }

    @Test
    fun navHandoffNeverCrashesOnOddNames() {
        val ctx = CarTestHost.carContext()
        NavHandoff.toPlace(ctx, 0.0, 0.0, "")
        NavHandoff.toPlace(ctx, -90.0, 180.0, "\u0000 \n \t ((( )))")
        NavHandoff.toPlace(ctx, 38.5, -121.5, "N".repeat(5_000))
        assertEquals(3, ctx.startCarAppIntents.size)
        assertTrue(ctx.getCarService(TestAppManager::class.java).toastsShown.isEmpty())
    }
}
