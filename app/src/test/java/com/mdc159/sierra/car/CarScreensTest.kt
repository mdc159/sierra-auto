package com.mdc159.sierra.car

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.Exchange
import com.mdc159.sierra.agent.History
import com.mdc159.sierra.car.CarTestHost.asGrid
import com.mdc159.sierra.car.CarTestHost.asList
import com.mdc159.sierra.car.CarTestHost.asMap
import com.mdc159.sierra.car.CarTestHost.asPane
import com.mdc159.sierra.car.CarTestHost.gridItems
import com.mdc159.sierra.car.CarTestHost.rows
import com.mdc159.sierra.car.CarTestHost.textLines
import com.mdc159.sierra.car.CarTestHost.titleText
import com.mdc159.sierra.places.NearbyPlace
import com.mdc159.sierra.places.NearbyPlaces
import com.mdc159.sierra.places.PlaceCategory
import com.mdc159.sierra.places.PlaceSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Builds every car Screen on main (except Talk, see [TalkScreenTest]) through the Car App
 * Library's test host in each of its states. A template that breaks a host rule throws
 * from the builder, which is exactly the class of crash these tests exist to catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarScreensTest {

    private lateinit var carContext: TestCarContext
    private lateinit var session: SierraSession

    @Before
    fun setUp() {
        carContext = CarTestHost.carContext()
        session = CarTestHost.session(carContext)
        CarTestHost.installConfig(carContext, null)
        CarTestHost.denyAll()
        History.clear(carContext)
    }

    @After
    fun tearDown() {
        History.clear(carContext)
    }

    // ---- Home ----------------------------------------------------------------------------

    // Home is a four-item grid since lane C added Truck: Talk, Places, Truck, History.

    @Test
    fun homeBuildsUnconfiguredAndConfigured() {
        val unconfigured = HomeScreen(carContext, session)
        CarTestHost.show(unconfigured)
        val t1 = unconfigured.onGetTemplate().asGrid()
        assertEquals(listOf("Talk", "Places", "Truck", "History"), t1.gridItems.map { it.titleText })
        assertTrue(t1.gridItems.first().text.toString().contains("Set up"))
        assertTrue(t1.gridItems.size <= CarLimits.listLimit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_GRID))
        assertNull(t1.actionStrip)

        CarTestHost.installConfig(carContext, AgentConfig(baseUrl = "https://h", model = "m"))
        val configured = HomeScreen(carContext, session)
        CarTestHost.show(configured)
        val t2 = configured.onGetTemplate().asGrid()
        assertEquals("Ask anything", t2.gridItems.first().text.toString())
        // No adapter chosen yet, so the Truck tile says it is showing demo data.
        assertTrue(t2.gridItems.first { it.titleText == "Truck" }.text.toString().contains("demo"))
    }

    @Test
    fun homeRefreshesWhenStartedAndPushesTheRightScreens() {
        val home = HomeScreen(carContext, session)
        val controller = CarTestHost.show(home)
        // onStart invalidates, so the host already received a template.
        assertTrue(controller.templatesReturned.isNotEmpty())

        val items = home.onGetTemplate().asGrid().gridItems
        val talk = items.first { it.titleText == "Talk" }
        val places = items.first { it.titleText == "Places" }
        val history = items.first { it.titleText == "History" }

        CarTestHost.installConfig(carContext, null)
        CarTestHost.click(places)
        CarTestHost.click(history)
        CarTestHost.click(talk)
        val pushed = carContext.getCarService(TestScreenManager::class.java).screensPushed
        assertTrue(pushed.any { it is PlaceCategoriesScreen })
        assertTrue(pushed.any { it is HistoryScreen })
        assertTrue(pushed.any { it is TalkScreen })
    }

    @Test
    fun sessionCreatesHomeAsRootScreen() {
        val screen = session.onCreateScreen(android.content.Intent())
        assertTrue(screen is HomeScreen)
    }

    // ---- Place categories ----------------------------------------------------------------

    @Test
    fun placeCategoriesListsEveryCategoryWithinTheHostLimit() {
        val screen = PlaceCategoriesScreen(carContext)
        CarTestHost.show(screen)
        val list = screen.onGetTemplate().asList()
        assertEquals(PlaceCategory.entries.map { it.label }, list.rows.map { it.titleText })
        assertTrue(list.rows.size <= CarLimits.listLimit(carContext))
        assertTrue(list.rows.all { it.isBrowsable })

        CarTestHost.click(list.rows.first())
        val pushed = carContext.getCarService(TestScreenManager::class.java).screensPushed.last()
        assertTrue(pushed is PlaceListScreen)
    }

    @Test
    fun placeCategoriesBuildOnAnApiLevel1Host() {
        val old = CarTestHost.carContext(apiLevel = CarAppApiLevels.LEVEL_1)
        val screen = PlaceCategoriesScreen(old)
        CarTestHost.show(screen)
        assertEquals(PlaceCategory.entries.size, screen.onGetTemplate().asList().rows.size)
    }

    // ---- Place list ----------------------------------------------------------------------

    @Test
    fun placeListWithoutLocationPermissionShowsBrowsableStatusRows() {
        val screen = PlaceListScreen(carContext, PlaceCategory.FUEL, NearbyPlaces(listOf(neverCalled)))
        CarTestHost.show(screen)
        val map = screen.onGetTemplate().asMap()
        assertFalse(map.isLoading)
        assertEquals(2, map.rows.size)
        assertTrue(map.rows[0].titleText.startsWith("Allow location"))
        assertTrue(map.rows[1].titleText.contains("in Maps"))
        assertTrue(map.rows.all { it.isBrowsable })

        // Tapping the first row asks the phone for location.
        CarTestHost.click(map.rows[0])
        val request = carContext.lastPermissionRequestInfo!!
        assertTrue(request.permissionsRequested.contains(Manifest.permission.ACCESS_FINE_LOCATION))

        // Tapping the Maps row hands a search off to the navigation app.
        CarTestHost.click(map.rows[1])
        val intent = carContext.startCarAppIntents.single()
        assertEquals(CarContext.ACTION_NAVIGATE, intent.action)
        assertEquals("geo:0,0?q=gas%20station", intent.dataString)
    }

    @Test
    fun placeListGrantingPermissionReloads() {
        val source = FakeSource(listOf(place("Shell", 800.0)))
        val screen = PlaceListScreen(carContext, PlaceCategory.FUEL, NearbyPlaces(listOf(source)))
        CarTestHost.show(screen)
        CarTestHost.click(screen.onGetTemplate().asMap().rows[0])

        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        simulateLocation(38.5, -121.5)
        carContext.lastPermissionRequestInfo!!.listener.onRequestPermissionsResult(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION), emptyList(),
        )
        CarTestHost.await(what = "places to load") { source.calls.get() > 0 && loadedRows(screen) == 1 }
        assertEquals("Shell", screen.onGetTemplate().asMap().rows[0].titleText)
    }

    @Test
    fun placeListStartsLoadingThenShowsPlacesWithDistanceAndMarkers() {
        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        simulateLocation(38.5, -121.5)
        // The search is held open so the loading template can be read deterministically.
        val gate = CountDownLatch(1)
        val source = FakeSource(listOf(place("Shell", 800.0, "12 Main St, Davis"), place("Chevron", 2400.0, null)), gate)
        val screen = PlaceListScreen(carContext, PlaceCategory.FUEL, NearbyPlaces(listOf(source)))
        CarTestHost.show(screen)

        val loading = screen.onGetTemplate().asMap()
        assertTrue(loading.isLoading)
        assertNull(loading.itemList)
        assertNotNull("API 5+ host uses the host refresh control", loading.onContentRefreshDelegate)
        assertNull(loading.actionStrip)

        gate.countDown()
        CarTestHost.await(what = "places to load") { loadedRows(screen) == 2 }
        val loaded = screen.onGetTemplate().asMap()
        assertFalse(loaded.isLoading)
        assertTrue(loaded.isCurrentLocationEnabled)
        assertNotNull(loaded.anchor)
        assertEquals(listOf("Shell", "Chevron"), loaded.rows.map { it.titleText })
        loaded.rows.forEach { row ->
            assertFalse(row.isBrowsable)
            assertNotNull(row.onClickDelegate)
            assertNotNull("every place row carries a map marker", row.metadata!!.place!!.marker)
            val spanned = row.texts.first().toCharSequence() as android.text.Spanned
            assertEquals(1, spanned.getSpans(0, spanned.length, DistanceSpan::class.java).size)
        }
        assertEquals(2, loaded.rows[0].texts.size)
        assertEquals("12 Main St, Davis", loaded.rows[0].textLines[1])
        assertEquals(1, loaded.rows[1].texts.size)

        CarTestHost.click(loaded.rows[0])
        assertTrue(carContext.getCarService(TestScreenManager::class.java).screensPushed.last() is PlaceDetailScreen)
    }

    @Test
    fun placeListEmptyResultBuilds() {
        CarTestHost.grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        simulateLocation(38.5, -121.5)
        val screen = PlaceListScreen(carContext, PlaceCategory.REST_AREAS, NearbyPlaces(listOf(FakeSource(emptyList()))))
        CarTestHost.show(screen)
        CarTestHost.await(what = "empty load") { !screen.onGetTemplate().asMap().isLoading }
        val map = screen.onGetTemplate().asMap()
        assertEquals(0, map.rows.size)
        assertEquals("No rest areas found nearby.", map.itemList!!.noItemsMessage.toString())
    }

    @Test
    fun placeListNeverSendsMoreRowsThanTheHostLimit() {
        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        simulateLocation(38.5, -121.5)
        // A misbehaving source ignores the limit it was given.
        val many = (1..40).map { place("Station $it", it * 100.0) }
        val source = FakeSource(many)
        val screen = PlaceListScreen(carContext, PlaceCategory.FUEL, NearbyPlaces(listOf(source)))
        CarTestHost.show(screen)
        CarTestHost.await(what = "load") { !screen.onGetTemplate().asMap().isLoading }
        val limit = CarLimits.placeListLimit(carContext)
        assertEquals(limit, source.limitSeen.get())
        assertTrue(screen.onGetTemplate().asMap().rows.size <= limit)
    }

    @Test
    fun placeListSurvivesVeryLongAndOddPlaceNames() {
        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        simulateLocation(38.5, -121.5)
        val odd = listOf(
            place("N".repeat(2_000), 50.0, "D".repeat(1_000)),
            place("Joe's \"Diner\" (24h) <b>&amp;</b> 🍔", 60.0, "   "),
            place("   ", 70.0, ""),
        )
        val screen = PlaceListScreen(carContext, PlaceCategory.FOOD, NearbyPlaces(listOf(FakeSource(odd))))
        CarTestHost.show(screen)
        CarTestHost.await(what = "load") { !screen.onGetTemplate().asMap().isLoading }
        val rows = screen.onGetTemplate().asMap().rows
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.titleText.isNotBlank() })
        assertTrue(rows.all { it.texts.size in 1..2 })

        // The detail screen must build for the same places.
        odd.forEach { p ->
            val detail = PlaceDetailScreen(carContext, p)
            CarTestHost.show(detail)
            val pane = detail.onGetTemplate().asPane()
            assertTrue(pane.pane.rows.size in 1..2)
        }
    }

    @Test
    fun placeListSourceFailureShowsRetryAndMapsRows() {
        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        simulateLocation(38.5, -121.5)
        val failing = PlaceSource { _, _, _, _ -> throw IOException("Places server busy (504).") }
        val screen = PlaceListScreen(carContext, PlaceCategory.PARKING, NearbyPlaces(listOf(failing)))
        CarTestHost.show(screen)
        CarTestHost.await(what = "failure") { !screen.onGetTemplate().asMap().isLoading }
        val map = screen.onGetTemplate().asMap()
        assertEquals(2, map.rows.size)
        assertTrue(map.rows[0].titleText.startsWith("Couldn't load places"))
        assertEquals("Places server busy (504).", map.rows[0].textLines.single())
        assertTrue(map.rows.all { it.isBrowsable })
    }

    @Test
    fun placeListWithoutAnyLocationFixReportsIt() {
        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = carContext.getSystemService(LocationManager::class.java)!!
        lm.allProviders.forEach { shadowOf(lm).setProviderEnabled(it, false) }
        val screen = PlaceListScreen(carContext, PlaceCategory.FUEL, NearbyPlaces(listOf(neverCalled)))
        CarTestHost.show(screen)
        CarTestHost.await(what = "no fix") { !screen.onGetTemplate().asMap().isLoading }
        assertEquals("No location fix yet.", screen.onGetTemplate().asMap().rows[0].textLines.single())
    }

    @Test
    fun placeListOnOldHostUsesARefreshActionInsteadOfHostRefresh() {
        val old = CarTestHost.carContext(apiLevel = CarAppApiLevels.LEVEL_3)
        val screen = PlaceListScreen(old, PlaceCategory.FUEL, NearbyPlaces(listOf(neverCalled)))
        CarTestHost.show(screen)
        val map = screen.onGetTemplate().asMap()
        assertNull(map.onContentRefreshDelegate)
        assertEquals("Refresh", map.actionStrip!!.actions.single().title.toString())
    }

    @Test
    fun placeListRefreshKeepsRowsOnScreenWhileReloading() {
        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        simulateLocation(38.5, -121.5)
        val gate = CountDownLatch(1)
        val source = FakeSource(listOf(place("Shell", 800.0)), gate)
        val screen = PlaceListScreen(carContext, PlaceCategory.FUEL, NearbyPlaces(listOf(source)))
        CarTestHost.show(screen)
        gate.countDown()
        CarTestHost.await(what = "first load") { loadedRows(screen) == 1 }

        screen.onGetTemplate().asMap().onContentRefreshDelegate!!.sendContentRefreshRequested(
            object : androidx.car.app.OnDoneCallback {
                override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {}
                override fun onFailure(response: androidx.car.app.serialization.Bundleable) {}
            }
        )
        // While the second search runs the template is still the loaded list, not a spinner.
        val during = screen.onGetTemplate().asMap()
        assertFalse(during.isLoading)
        assertEquals(1, during.rows.size)
        CarTestHost.await(what = "second load") { source.calls.get() == 2 }
    }

    @Test
    fun placeListCancelsItsSearchWhenDestroyed() {
        CarTestHost.grant(Manifest.permission.ACCESS_FINE_LOCATION)
        simulateLocation(38.5, -121.5)
        val gate = CountDownLatch(1)
        val source = FakeSource(listOf(place("Shell", 800.0)), gate)
        val screen = PlaceListScreen(carContext, PlaceCategory.FUEL, NearbyPlaces(listOf(source)))
        val controller = CarTestHost.show(screen)
        CarTestHost.await(what = "search to start") { source.calls.get() == 1 }
        controller.moveToState(Lifecycle.State.DESTROYED)
        gate.countDown()
        CarTestHost.await(what = "interrupt") { source.interrupted.get() }
    }

    // ---- Place detail --------------------------------------------------------------------

    @Test
    fun placeDetailShowsDistanceAndNavigates() {
        val p = place("Shell", 1609.344 * 2.5, "12 Main St")
        val screen = PlaceDetailScreen(carContext, p)
        CarTestHost.show(screen)
        val pane = screen.onGetTemplate().asPane()
        assertEquals("Shell", pane.title.toString())
        assertEquals(2, pane.pane.rows.size)
        assertEquals("2.5 mi away", (pane.pane.rows[0] as androidx.car.app.model.Row).textLines.single())
        val navigate = pane.pane.actions.single()
        assertEquals(Action.FLAG_PRIMARY, navigate.flags and Action.FLAG_PRIMARY)

        CarTestHost.click(navigate)
        val intent = carContext.startCarAppIntents.single()
        assertEquals(CarContext.ACTION_NAVIGATE, intent.action)
        assertTrue(intent.dataString!!.startsWith("geo:0,0?q=${p.lat},${p.lng}("))
    }

    @Test
    fun placeDetailWithoutDetailHasOneRow() {
        val screen = PlaceDetailScreen(carContext, place("Shell", 10.0, null))
        CarTestHost.show(screen)
        assertEquals(1, screen.onGetTemplate().asPane().pane.rows.size)
    }

    // ---- History -------------------------------------------------------------------------

    @Test
    fun historyEmptyShowsNoItemsMessage() {
        val screen = HistoryScreen(carContext, session)
        CarTestHost.show(screen)
        val list = screen.onGetTemplate().asList()
        assertEquals(0, list.rows.size)
        assertTrue(list.singleList!!.noItemsMessage.toString().contains("No conversations"))
    }

    @Test
    fun historyCapsAtTheHostLimitNewestFirst() {
        repeat(History.MAX_ENTRIES + 3) { History.add(carContext, "q$it", "a$it", now = it.toLong()) }
        val screen = HistoryScreen(carContext, session)
        CarTestHost.show(screen)
        val list = screen.onGetTemplate().asList()
        val limit = CarLimits.listLimit(carContext)
        assertEquals(limit, list.rows.size)
        assertEquals("q${History.MAX_ENTRIES + 2}", list.rows.first().titleText)
        assertTrue(list.rows.all { it.isBrowsable && it.texts.size == 1 })

        CarTestHost.click(list.rows.first())
        assertTrue(carContext.getCarService(TestScreenManager::class.java).screensPushed.last() is HistoryDetailScreen)
    }

    @Test
    fun historySurvivesVeryLongAndMarkdownOnlyText() {
        History.add(carContext, "Q".repeat(5_000), "A".repeat(5_000) + " end.")
        History.add(carContext, "**", "_`#_")
        History.add(carContext, "", "answer only")
        val screen = HistoryScreen(carContext, session)
        CarTestHost.show(screen)
        val list = screen.onGetTemplate().asList()
        assertEquals(3, list.rows.size)
        assertTrue(list.rows.all { it.titleText.isNotBlank() && it.titleText.length <= 60 })

        History.list(carContext).forEach { e ->
            val detail = HistoryDetailScreen(carContext, session, e)
            CarTestHost.show(detail)
            val pane = detail.onGetTemplate().asPane()
            assertTrue(pane.pane.rows.size in 1..CarLimits.listLimit(carContext, androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_PANE))
            assertEquals("Hear it", pane.pane.actions.single().title.toString())
        }
    }

    @Test
    fun historyDetailSplitsTheAnswerIntoPaneRows() {
        val exchange = Exchange(0L, "Where is fuel?", "Exit in two miles. The station is on the right. It is open all night.")
        val screen = HistoryDetailScreen(carContext, session, exchange)
        CarTestHost.show(screen)
        val pane = screen.onGetTemplate().asPane()
        val rows = pane.pane.rows.map { it as androidx.car.app.model.Row }
        assertEquals("You: Where is fuel?", rows.first().titleText)
        assertTrue(rows.size >= 2)
        assertTrue(rows.drop(1).joinToString(" ") { it.titleText }.contains("open all night"))
        CarTestHost.click(pane.pane.actions.single()) // "Hear it" must not throw without a TTS engine
    }

    // ---- helpers -------------------------------------------------------------------------

    private val neverCalled = PlaceSource { _, _, _, _ -> throw AssertionError("source must not be called") }

    private fun place(name: String, meters: Double, detail: String? = null) =
        NearbyPlace(name, 38.5 + meters / 111_000.0, -121.5, meters, detail)

    private fun loadedRows(screen: PlaceListScreen): Int {
        val t = screen.onGetTemplate() as PlaceListMapTemplate
        return if (t.isLoading) -1 else t.rows.count { !it.isBrowsable }
    }

    private fun simulateLocation(lat: Double, lng: Double) {
        val lm = carContext.getSystemService(LocationManager::class.java)!!
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lng
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        shadowOf(lm).simulateLocation(location)
    }

    /** A place source that records calls and can be held open to observe cancellation. */
    private class FakeSource(
        private val result: List<NearbyPlace>,
        private val gate: CountDownLatch? = null,
    ) : PlaceSource {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val limitSeen = java.util.concurrent.atomic.AtomicInteger()
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()

        override fun search(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace> {
            calls.incrementAndGet()
            limitSeen.set(limit)
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS)
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                    throw IOException("interrupted", e)
                }
            }
            return result
        }
    }
}
