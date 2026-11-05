package com.mdc159.sierra.places

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Malformed, empty and slow answers from the keyless place sources. */
class PlaceSourceEdgeCasesTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(1, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun nominatim() = NominatimClient(client, server.url("/search").toString(), requestSpacingMs = 0)
    private fun overpass() = OverpassClient(client, listOf(server.url("/api/interpreter").toString()))

    // ---- Nominatim ------------------------------------------------------------------------

    @Test
    fun nominatimErrorObjectInsteadOfArrayIsABadResponse() {
        server.enqueue(MockResponse().setBody("""{"error":"Unable to geocode"}"""))
        assertThrows(org.json.JSONException::class.java) {
            nominatim().search(PlaceCategory.FUEL, 38.5, -121.5, 6)
        }
        // NearbyPlaces turns that into a car-screen message and moves to the next source.
        server.enqueue(MockResponse().setBody("""{"error":"Unable to geocode"}"""))
        val e = assertThrows(IOException::class.java) {
            NearbyPlaces(listOf(nominatim())).search(PlaceCategory.FUEL, 38.5, -121.5, 6)
        }
        assertEquals("Places server sent a bad response.", e.message)
    }

    @Test
    fun nominatimHtmlAndEmptyBodiesAreBadResponses() {
        for (body in listOf("<html>Too many requests</html>", "", "null", "[1, 2, \"x\"]")) {
            server.enqueue(MockResponse().setBody(body))
            val result = runCatching { nominatim().search(PlaceCategory.FUEL, 38.5, -121.5, 6) }
            val ok = result.isSuccess && result.getOrThrow().isEmpty()
            val bad = result.exceptionOrNull() is org.json.JSONException || result.exceptionOrNull() is IOException
            assertTrue("body '$body' must be empty or a readable failure", ok || bad)
        }
    }

    @Test
    fun nominatimSkipsEntriesWithBadCoordinatesAndFillsMissingNames() {
        server.enqueue(
            MockResponse().setBody(
                """[{"lat":"38.501","lon":"-121.5"},
                    {"lat":38.502,"lon":-121.5,"name":"Numeric coords","address":{"road":"Elm St","town":"Woodland"}},
                    {"lat":"38.503"},
                    {"lon":"-121.5"},
                    "not an object",
                    {"lat":"91","lon":"-121.5","name":"Far away"}]"""
            )
        )
        val places = nominatim().search(PlaceCategory.FUEL, 38.5, -121.5, 6)
        assertEquals(listOf("Fuel", "Numeric coords"), places.map { it.name })
        assertEquals("Elm St, Woodland", places[1].detail)
        assertEquals(null, places[0].detail)
    }

    @Test
    fun nominatimTimeoutBecomesAReadableMessageThroughNearbyPlaces() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val e = assertThrows(IOException::class.java) {
            NearbyPlaces(listOf(nominatim())).search(PlaceCategory.FUEL, 38.5, -121.5, 6)
        }
        assertEquals("Timed out. Check signal and try again.", e.message)
    }

    @Test
    fun nominatimDisconnectMidBodyIsReported() {
        server.enqueue(MockResponse().setBody("[{\"lat\":\"38.5\"").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val e = assertThrows(IOException::class.java) {
            NearbyPlaces(listOf(nominatim())).search(PlaceCategory.FUEL, 38.5, -121.5, 6)
        }
        assertTrue(e.message!!.isNotBlank())
    }

    @Test
    fun nominatimRespectsTheLimitAndTheRadius() {
        val rows = (1..20).joinToString(",") { i ->
            // 0.01 deg of latitude is ~1.1 km; row 20 is ~22 km out, past the 8 km fuel radius.
            """{"lat":"${38.5 + i * 0.01}","lon":"-121.5","name":"P$i"}"""
        }
        server.enqueue(MockResponse().setBody("[$rows]"))
        val places = nominatim().search(PlaceCategory.FUEL, 38.5, -121.5, 4)
        assertEquals(4, places.size)
        assertEquals(listOf("P1", "P2", "P3", "P4"), places.map { it.name })
        assertTrue(places.all { it.distanceMeters <= PlaceCategory.FUEL.radiusMeters * 1.5 })
    }

    // ---- Overpass -------------------------------------------------------------------------

    @Test
    fun overpassMalformedShapesParseToNothingInsteadOfCrashing() {
        for (body in listOf("{}", """{"elements":null}""", """{"elements":{}}""", """{"elements":[null, 1, "x", {}]}""")) {
            assertEquals("body $body", emptyList<NearbyPlace>(), OverpassClient.parse(body, PlaceCategory.FUEL, 38.5, -121.5))
        }
    }

    @Test
    fun overpassRejectsNonObjectBodies() {
        for (body in listOf("[]", "<html>busy</html>", "", "null")) {
            assertThrows("body '$body'", org.json.JSONException::class.java) {
                OverpassClient.parse(body, PlaceCategory.FUEL, 38.5, -121.5)
            }
        }
    }

    @Test
    fun overpassToleratesStringCoordinatesAndMissingTags() {
        val body = """{"elements":[
            {"type":"node","id":1,"lat":"38.501","lon":"-121.5"},
            {"type":"way","id":2,"center":{"lat":"x","lon":"-121.5"},"tags":{"name":"Bad center"}},
            {"type":"relation","id":3,"center":{"lat":38.502,"lon":-121.5},"tags":null}
        ]}"""
        val places = OverpassClient.parse(body, PlaceCategory.PARKING, 38.5, -121.5)
        assertEquals(listOf("Parking", "Parking"), places.map { it.name })
        assertTrue(places[0].distanceMeters < places[1].distanceMeters)
    }

    @Test
    fun overpassAllEndpointsTimingOutIsOneReadableError() {
        val mirror = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            mirror.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OverpassClient(
                client,
                listOf(server.url("/api/interpreter").toString(), mirror.url("/api/interpreter").toString()),
            )
            val e = assertThrows(IOException::class.java) { client.search(PlaceCategory.FUEL, 38.5, -121.5, 6) }
            assertEquals("Timed out. Check signal and try again.", e.message)
            assertEquals(1, server.requestCount)
            assertEquals(1, mirror.requestCount)
        } finally {
            mirror.shutdown()
        }
    }

    @Test
    fun overpassEmptyBodyFallsThrough() {
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody("""{"elements":[]}"""))
        val client = OverpassClient(
            client,
            listOf(server.url("/api/interpreter").toString(), server.url("/api/interpreter").toString()),
        )
        assertEquals(emptyList<NearbyPlace>(), client.search(PlaceCategory.FUEL, 38.5, -121.5, 6))
    }

    // ---- NearbyPlaces ----------------------------------------------------------------------

    @Test
    fun firstSourceWithAnAnswerWinsEvenWhenEmpty() {
        val calls = AtomicInteger()
        val empty = PlaceSource { _, _, _, _ -> calls.incrementAndGet(); emptyList() }
        val never = PlaceSource { _, _, _, _ -> throw AssertionError("second source must not run") }
        assertEquals(emptyList<NearbyPlace>(), NearbyPlaces(listOf(empty, never)).search(PlaceCategory.FUEL, 0.0, 0.0, 6))
        assertEquals(1, calls.get())
    }

    @Test
    fun jsonErrorsFromASourceFallThroughLikeNetworkErrors() {
        val broken = PlaceSource { _, _, _, _ -> throw org.json.JSONException("bad") }
        val working = PlaceSource { _, _, _, _ -> listOf(NearbyPlace("Ok", 1.0, 2.0, 3.0, null)) }
        assertEquals("Ok", NearbyPlaces(listOf(broken, working)).search(PlaceCategory.FUEL, 0.0, 0.0, 6).single().name)
        val e = assertThrows(IOException::class.java) {
            NearbyPlaces(listOf(broken)).search(PlaceCategory.FUEL, 0.0, 0.0, 6)
        }
        assertEquals("Places server sent a bad response.", e.message)
    }

    @Test
    fun noSourcesIsAnErrorNotAnEmptyList() {
        val e = assertThrows(IOException::class.java) { NearbyPlaces(emptyList()).search(PlaceCategory.FUEL, 0.0, 0.0, 6) }
        assertEquals("No place source configured.", e.message)
    }

    @Test
    fun runtimeFailuresOtherThanJsonAreNotSwallowed() {
        val crash = PlaceSource { _, _, _, _ -> throw IllegalStateException("bug") }
        assertThrows(IllegalStateException::class.java) { NearbyPlaces(listOf(crash)).search(PlaceCategory.FUEL, 0.0, 0.0, 6) }
    }

    @Test
    fun geoDedupeKeepsTheNearestOfNearIdenticalEntries() {
        val a = NearbyPlace("Shell", 38.50001, -121.50001, 900.0, null)
        val b = NearbyPlace("Shell", 38.50002, -121.50002, 300.0, "12 Main St")
        val c = NearbyPlace("Shell", 38.6, -121.5, 100.0, null)
        val out = Geo.dedupeAndSort(listOf(a, b, c))
        assertEquals(2, out.size)
        assertEquals(100.0, out[0].distanceMeters, 0.0)
        assertEquals("Shell", out[1].name)
    }

    @Test
    fun viewboxNearThePolesDoesNotBlowUp() {
        val box = Geo.viewbox(89.9999, 0.0, 8_000).split(",").map { it.toDouble() }
        assertTrue(box.all { it.isFinite() })
    }
}
