package com.mdc159.sierra.places

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NominatimClientTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun nominatim() = NominatimClient(client, server.url("/search").toString(), requestSpacingMs = 0)

    @Test
    fun queriesEveryTermAndSortsByDistance() {
        server.enqueue(MockResponse().setBody(FAST_FOOD))
        server.enqueue(MockResponse().setBody(RESTAURANT))
        val places = nominatim().search(PlaceCategory.FOOD, 38.5, -121.5, limit = 6)

        assertEquals(listOf("Taco Stop", "Diner", "Food"), places.map { it.name })
        assertEquals("5 Oak Ave, Davis", places[0].detail)

        val first = server.takeRequest().requestUrl!!
        assertEquals("fast food", first.queryParameter("amenity"))
        assertEquals("1", first.queryParameter("bounded"))
        assertTrue(first.queryParameter("viewbox")!!.split(",").size == 4)
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("amenity") == "restaurant")
    }

    @Test
    fun serverErrorThrows() {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            nominatim().search(PlaceCategory.FUEL, 38.5, -121.5, limit = 6)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("503"))
        }
    }

    @Test
    fun nearbyPlacesFallsThroughToNextSource() {
        val failing = PlaceSource { _, _, _, _ -> throw IOException("down") }
        val working = PlaceSource { _, _, _, _ -> listOf(NearbyPlace("Ok", 1.0, 2.0, 3.0, null)) }
        val result = NearbyPlaces(listOf(failing, working)).search(PlaceCategory.FUEL, 0.0, 0.0, 6)
        assertEquals("Ok", result.single().name)
    }

    @Test
    fun nearbyPlacesReportsLastError() {
        val failing = PlaceSource { _, _, _, _ -> throw IOException("Places server busy (504).") }
        try {
            NearbyPlaces(listOf(failing, failing)).search(PlaceCategory.FUEL, 0.0, 0.0, 6)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Places server busy (504).", e.message)
        }
    }

    @Test
    fun geoHelpers() {
        val d = Geo.distanceMeters(38.0, -121.0, 39.0, -121.0)
        assertTrue(d in 110_000.0..112_500.0)
        val box = Geo.viewbox(38.5, -121.5, 8000).split(",").map { it.toDouble() }
        assertTrue(box[0] < -121.5 && box[2] > -121.5)
        assertTrue(box[1] > 38.5 && box[3] < 38.5)
    }

    companion object {
        val FAST_FOOD = """
            [{"lat":"38.501","lon":"-121.5","name":"Taco Stop",
              "address":{"house_number":"5","road":"Oak Ave","city":"Davis"}},
             {"lat":"38.53","lon":"-121.5","name":""}]
        """.trimIndent()
        val RESTAURANT = """
            [{"lat":"38.51","lon":"-121.5","name":"Diner","address":{}},
             {"lat":"bad","lon":"-121.5","name":"Broken"}]
        """.trimIndent()
    }
}
