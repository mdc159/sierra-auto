package com.mdc159.sierra.places

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OverpassClientTest {

    private lateinit var primary: MockWebServer
    private lateinit var mirror: MockWebServer
    private val client = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        primary = MockWebServer().apply { start() }
        mirror = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        primary.shutdown()
        mirror.shutdown()
    }

    private fun overpass() = OverpassClient(
        client,
        listOf(primary.url("/api/interpreter").toString(), mirror.url("/api/interpreter").toString()),
    )

    @Test
    fun buildsAroundQueryForEveryFilter() {
        val q = OverpassClient.buildQuery(PlaceCategory.REST_AREAS, 38.5, -121.5, 8000)
        assertTrue(q.startsWith("[out:json]"))
        assertTrue(q.contains("nwr[\"highway\"=\"rest_area\"](around:8000,38.500000,-121.500000);"))
        assertTrue(q.contains("nwr[\"highway\"=\"services\"](around:8000,38.500000,-121.500000);"))
    }

    @Test
    fun parsesNodesAndWaysSortedByDistance() {
        val places = OverpassClient.parse(SAMPLE, PlaceCategory.FUEL, 38.5, -121.5)
        assertEquals(3, places.size)
        assertEquals("Near Gas", places[0].name)
        assertEquals("Shell", places[1].name) // unnamed way falls back to brand
        assertEquals("Fuel", places[2].name) // no name or brand falls back to category
        assertEquals("12 Main St, Davis", places[0].detail)
        assertTrue(places[0].distanceMeters < places[1].distanceMeters)
    }

    @Test
    fun searchPostsQueryAndLimitsResults() {
        primary.enqueue(MockResponse().setBody(SAMPLE))
        val places = overpass().search(PlaceCategory.FUEL, 38.5, -121.5, limit = 2)
        assertEquals(2, places.size)
        val request = primary.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().startsWith("data="))
    }

    @Test
    fun fallsBackToMirrorOnServerError() {
        primary.enqueue(MockResponse().setResponseCode(504))
        mirror.enqueue(MockResponse().setBody(SAMPLE))
        val places = overpass().search(PlaceCategory.FUEL, 38.5, -121.5, limit = 6)
        assertEquals(3, places.size)
    }

    @Test
    fun fallsBackToMirrorOnMalformedBody() {
        primary.enqueue(MockResponse().setBody("<html>busy</html>"))
        mirror.enqueue(MockResponse().setBody(SAMPLE))
        assertEquals(3, overpass().search(PlaceCategory.FUEL, 38.5, -121.5, limit = 6).size)
    }

    @Test
    fun timeoutBecomesReadableError() {
        primary.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        mirror.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try {
            overpass().search(PlaceCategory.FUEL, 38.5, -121.5, limit = 6)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Timed out. Check signal and try again.", e.message)
        }
    }

    companion object {
        val SAMPLE = """
            {"elements":[
              {"type":"way","id":2,"center":{"lat":38.52,"lon":-121.5},"tags":{"brand":"Shell"}},
              {"type":"node","id":1,"lat":38.501,"lon":-121.5,
               "tags":{"name":"Near Gas","addr:housenumber":"12","addr:street":"Main St","addr:city":"Davis"}},
              {"type":"node","id":3,"lat":38.6,"lon":-121.5,"tags":{}},
              {"type":"node","id":4,"tags":{"name":"No coords"}}
            ]}
        """.trimIndent()
    }
}
