package com.mdc159.sierra.agent.tools

import com.mdc159.sierra.places.NearbyPlace
import com.mdc159.sierra.places.NearbyPlaces
import com.mdc159.sierra.places.PlaceCategory
import com.mdc159.sierra.places.PlaceSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FindPlacesToolTest {

    private val here = LocationProvider { LatLng(38.5, -121.5) }

    private fun source(vararg places: NearbyPlace, record: MutableList<PlaceCategory> = mutableListOf()) =
        NearbyPlaces(
            listOf(
                PlaceSource { category, _, _, limit ->
                    record += category
                    places.toList().take(limit)
                },
            ),
        )

    @Test
    fun mapsDriverWordsToCategories() {
        assertEquals(PlaceCategory.FUEL, FindPlacesTool.categoryFor("fuel"))
        assertEquals(PlaceCategory.FUEL, FindPlacesTool.categoryFor("Gas station"))
        assertEquals(PlaceCategory.FUEL, FindPlacesTool.categoryFor("where can I get diesel"))
        assertEquals(PlaceCategory.FOOD, FindPlacesTool.categoryFor("somewhere to eat"))
        assertEquals(PlaceCategory.FOOD, FindPlacesTool.categoryFor("coffee"))
        assertEquals(PlaceCategory.FOOD, FindPlacesTool.categoryFor("fast food"))
        assertEquals(PlaceCategory.REST_AREAS, FindPlacesTool.categoryFor("rest areas"))
        assertEquals(PlaceCategory.REST_AREAS, FindPlacesTool.categoryFor("a bathroom"))
        assertEquals(PlaceCategory.PARKING, FindPlacesTool.categoryFor("parking"))
        assertEquals(PlaceCategory.PARKING, FindPlacesTool.categoryFor("a place to park"))
        assertNull(FindPlacesTool.categoryFor("hardware store"))
        assertNull(FindPlacesTool.categoryFor(""))
    }

    @Test
    fun returnsUpToSixNearestWithCoordinates() {
        val many = (1..10).map { NearbyPlace("P$it", 38.5 + it * 0.001, -121.5, it * 500.0, if (it == 1) "1 Main St" else null) }
        val asked = mutableListOf<PlaceCategory>()
        val tool = FindPlacesTool(here, source(*many.toTypedArray(), record = asked))

        val out = JSONObject(tool.run(JSONObject().put("query", "gas")))
        assertEquals(listOf(PlaceCategory.FUEL), asked)
        assertEquals("Fuel", out.getString("category"))
        val results = out.getJSONArray("results")
        assertEquals(FindPlacesTool.MAX_RESULTS, results.length())
        val first = results.getJSONObject(0)
        assertEquals("P1", first.getString("name"))
        assertEquals(0.3, first.getDouble("distance_miles"), 0.001)
        assertEquals(38.501, first.getDouble("lat"), 1e-9)
        assertEquals(-121.5, first.getDouble("lng"), 1e-9)
        assertEquals("1 Main St", first.getString("address"))
        assertEquals("Finding fuel nearby…", tool.status(JSONObject().put("query", "gas")))
    }

    @Test
    fun unknownQueryGetsEmptyResultsAndAHint() {
        val tool = FindPlacesTool(here, source())
        val out = JSONObject(tool.run(JSONObject().put("query", "hardware store")))
        assertEquals(0, out.getJSONArray("results").length())
        assertTrue(out.getString("note").contains("navigate_to"))
        assertEquals("Finding hardware store nearby…", tool.status(JSONObject().put("query", "hardware store")))
    }

    @Test
    fun noLocationIsAToolError() {
        val tool = FindPlacesTool({ null }, source())
        try {
            tool.run(JSONObject().put("query", "fuel"))
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("location"))
        }
    }

    @Test
    fun placeSourceFailureIsAToolError() {
        val failing = NearbyPlaces(listOf(PlaceSource { _, _, _, _ -> throw IOException("Places server busy (504).") }))
        try {
            FindPlacesTool(here, failing).run(JSONObject().put("query", "food"))
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertEquals("Places server busy (504).", e.message)
        }
    }

    @Test
    fun emptyQueryIsAToolError() {
        try {
            FindPlacesTool(here, source()).run(JSONObject())
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertEquals("Say what to look for.", e.message)
        }
    }

    @Test
    fun specIsOpenAiShaped() {
        val spec = FindPlacesTool(here, source()).spec()
        assertEquals("function", spec.getString("type"))
        val fn = spec.getJSONObject("function")
        assertEquals("find_places", fn.getString("name"))
        assertEquals("object", fn.getJSONObject("parameters").getString("type"))
        assertEquals("query", fn.getJSONObject("parameters").getJSONArray("required").getString(0))
    }
}
