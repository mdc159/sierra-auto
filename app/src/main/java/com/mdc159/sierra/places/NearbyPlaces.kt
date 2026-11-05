package com.mdc159.sierra.places

import com.mdc159.sierra.net.Http
import java.io.IOException

data class NearbyPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double,
    val detail: String?,
)

/** A keyless source of nearby places. Throws [IOException] on failure. */
fun interface PlaceSource {
    fun search(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace>
}

/**
 * Tries each source in order and returns the first answer, including an empty one.
 * If every source fails, throws an [IOException] whose message is fit for the car screen.
 */
class NearbyPlaces(
    private val sources: List<PlaceSource> = listOf(NominatimClient(), OverpassClient()),
) {
    fun search(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace> {
        var lastError: Exception? = null
        for (source in sources) {
            try {
                return source.search(category, lat, lng, limit)
            } catch (e: IOException) {
                lastError = e
            } catch (e: org.json.JSONException) {
                lastError = IOException("Places server sent a bad response.", e)
            }
        }
        throw IOException(Http.describe(lastError ?: IOException("No place source configured.")), lastError)
    }
}
