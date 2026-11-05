package com.mdc159.sierra.places

/**
 * The small, fixed set of things a driver usually looks for.
 * [overpassFilters] are Overpass QL tag filters; any one matching is enough.
 * [nominatimTerms] are Nominatim structured `amenity=` search terms.
 * [radiusMeters] is how far to look. [mapsQuery] is the fallback search handed to the
 * navigation app when every place source fails.
 */
enum class PlaceCategory(
    val label: String,
    val overpassFilters: List<String>,
    val nominatimTerms: List<String>,
    val radiusMeters: Int,
    val mapsQuery: String,
) {
    FUEL("Fuel", listOf("[\"amenity\"=\"fuel\"]"), listOf("fuel"), 8_000, "gas station"),
    FOOD(
        "Food",
        listOf("[\"amenity\"~\"^(restaurant|fast_food|cafe)$\"]"),
        listOf("fast food", "restaurant"),
        8_000,
        "restaurants",
    ),
    REST_AREAS(
        "Rest areas",
        listOf("[\"highway\"=\"rest_area\"]", "[\"highway\"=\"services\"]"),
        listOf("rest area"),
        40_000,
        "rest area",
    ),
    PARKING("Parking", listOf("[\"amenity\"=\"parking\"]"), listOf("parking"), 5_000, "parking"),
}
