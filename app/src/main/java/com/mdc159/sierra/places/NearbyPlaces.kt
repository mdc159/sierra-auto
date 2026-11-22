package com.mdc159.sierra.places

import com.mdc159.sierra.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException

data class NearbyPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double,
    val detail: String?,
)

/** A row title the host accepts: never blank, no stray whitespace. */
val NearbyPlace.displayName: String get() = name.trim().ifBlank { "Unnamed place" }

/** Secondary text, or null when there is nothing worth a line. */
val NearbyPlace.displayDetail: String? get() = detail?.trim()?.ifBlank { null }

/** A keyless source of nearby places. Throws [IOException] on failure. */
fun interface PlaceSource {
    fun search(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace>

    /**
     * [search] with the caller's cancellation wired to the underlying HTTP call, so leaving
     * the screen releases a stalled request at once. The default keeps the old interruptible
     * blocking call, which is right for sources with no HTTP of their own to abort; the HTTP
     * sources override it with a cancellable call.
     */
    suspend fun searchCancellable(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace> =
        runInterruptible(Dispatchers.IO) { search(category, lat, lng, limit) }
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

    /** [search] with cancellation wired to each source's HTTP calls (see [PlaceSource.searchCancellable]). */
    suspend fun searchCancellable(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace> {
        var lastError: Exception? = null
        for (source in sources) {
            try {
                return source.searchCancellable(category, lat, lng, limit)
            } catch (e: IOException) {
                lastError = e
            } catch (e: org.json.JSONException) {
                lastError = IOException("Places server sent a bad response.", e)
            }
        }
        throw IOException(Http.describe(lastError ?: IOException("No place source configured.")), lastError)
    }
}
