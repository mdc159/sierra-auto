package com.mdc159.sierra.agent.tools

import com.mdc159.sierra.net.Http
import com.mdc159.sierra.places.NearbyPlaces
import com.mdc159.sierra.places.PlaceCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** Where the truck is right now, or null when the phone has no fix or no permission. */
fun interface LocationProvider {
    fun current(): LatLng?
}

data class LatLng(val lat: Double, val lng: Double)

/**
 * `find_places`: nearby places in one of the fixed categories the Places screen offers,
 * found with the same keyless OpenStreetMap clients and the phone's location.
 * A query that fits no category gets an empty result and a hint to use `navigate_to`
 * with just a name, which lets the navigation app search for it.
 */
class FindPlacesTool(
    private val location: LocationProvider,
    private val places: NearbyPlaces = NearbyPlaces(),
    private val limit: Int = MAX_RESULTS,
) : Tool {

    override val name = "find_places"

    override val description =
        "Finds places near the truck. Use it when the driver asks for fuel, gas, diesel, food, coffee, " +
            "a rest area, a restroom or parking nearby. Returns up to $MAX_RESULTS places, nearest first, " +
            "each with name, distance in miles, lat and lng for navigate_to."

    override val parameters: JSONObject = ToolSchema.obj(
        "query" to ToolSchema.string(
            "What to look for: a category (fuel, food, rest areas, parking) or the driver's own words, " +
                "such as \"coffee\" or \"somewhere to eat\".",
        ),
        required = listOf("query"),
    )

    override fun status(args: JSONObject): String {
        val category = categoryFor(args.optString("query"))
        val what = category?.label?.lowercase(Locale.US) ?: args.optString("query").ifBlank { "places" }
        return "Finding $what nearby…"
    }

    override fun run(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) throw ToolException("Say what to look for.")
        val category = categoryFor(query)
            ?: return JSONObject()
                .put("results", JSONArray())
                .put(
                    "note",
                    "Sierra can only search nearby fuel, food, rest areas and parking. For \"$query\", " +
                        "call navigate_to with only the name and the navigation app will search for it.",
                )
                .toString()
        val here = location.current() ?: throw ToolException("No location fix yet. Allow location on the phone and try again.")
        val found = try {
            places.search(category, here.lat, here.lng, limit.coerceIn(1, MAX_RESULTS))
        } catch (e: IOException) {
            throw ToolException(e.message ?: Http.describe(e), e)
        }
        val results = JSONArray()
        found.take(MAX_RESULTS).forEach { p ->
            val miles = p.distanceMeters / METERS_PER_MILE
            val entry = JSONObject()
                .put("name", p.name)
                .put("distance_miles", Math.round(miles * 10) / 10.0)
                .put("lat", p.lat)
                .put("lng", p.lng)
            p.detail?.let { entry.put("address", it) }
            results.put(entry)
        }
        return JSONObject()
            .put("category", category.label)
            .put("results", results)
            .toString()
    }

    companion object {
        const val MAX_RESULTS = 6
        private const val METERS_PER_MILE = 1609.344

        private val KEYWORDS: List<Pair<PlaceCategory, List<String>>> = listOf(
            PlaceCategory.FUEL to listOf("fuel", "gas", "diesel", "petrol", "fill up", "station", "pump"),
            PlaceCategory.REST_AREAS to listOf("rest area", "rest stop", "restroom", "bathroom", "toilet", "rest"),
            PlaceCategory.PARKING to listOf("parking", "park", "lot", "garage"),
            PlaceCategory.FOOD to listOf(
                "food", "eat", "restaurant", "lunch", "dinner", "breakfast", "coffee", "cafe", "burger",
                "taco", "pizza", "sandwich", "snack", "hungry", "drive-thru", "drive thru", "fast food", "diner",
            ),
        )

        /** Maps the driver's words to one of the Places categories, or null if none fits. */
        fun categoryFor(query: String): PlaceCategory? {
            val q = query.lowercase(Locale.US).trim()
            if (q.isEmpty()) return null
            PlaceCategory.entries.firstOrNull { it.label.lowercase(Locale.US) == q }?.let { return it }
            val words = q.split(Regex("[^a-z-]+")).filter { it.isNotEmpty() }.toSet()
            for ((category, keys) in KEYWORDS) {
                for (key in keys) {
                    val hit = if (key.contains(' ')) q.contains(key) else key in words
                    if (hit) return category
                }
            }
            return null
        }
    }
}
