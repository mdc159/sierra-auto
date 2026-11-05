package com.mdc159.sierra.places

import com.mdc159.sierra.net.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keyless nearby search using OpenStreetMap Nominatim's structured `amenity=` query,
 * bounded to a box around the driver. Results are re-sorted by distance.
 */
class NominatimClient(
    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://nominatim.openstreetmap.org/search",
) : PlaceSource {

    override fun search(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace> {
        val all = category.nominatimTerms.flatMap { term ->
            parse(get(buildUrl(baseUrl, term, lat, lng, category.radiusMeters)), category, lat, lng)
        }
        return Geo.dedupeAndSort(all)
            .filter { it.distanceMeters <= category.radiusMeters * 1.5 }
            .take(limit)
    }

    private fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Http.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Places server error (${response.code}).")
            return response.body?.string() ?: throw IOException("Empty places response.")
        }
    }

    companion object {
        fun buildUrl(baseUrl: String, term: String, lat: Double, lng: Double, radiusMeters: Int): HttpUrl =
            baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("amenity", term)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("bounded", "1")
                .addQueryParameter("limit", "40")
                .addQueryParameter("viewbox", Geo.viewbox(lat, lng, radiusMeters))
                .build()

        fun parse(json: String, category: PlaceCategory, lat: Double, lng: Double): List<NearbyPlace> {
            val arr = JSONArray(json)
            val places = ArrayList<NearbyPlace>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pLat = o.optString("lat").toDoubleOrNull() ?: continue
                val pLng = o.optString("lon").toDoubleOrNull() ?: continue
                val address = o.optJSONObject("address")
                fun addr(key: String): String? = address?.optString(key)?.ifBlank { null }
                val name = o.optString("name").ifBlank { null } ?: category.label
                val street = listOfNotNull(addr("house_number"), addr("road")).joinToString(" ").ifBlank { null }
                val city = addr("city") ?: addr("town") ?: addr("village")
                val detail = listOfNotNull(street, city).joinToString(", ").ifBlank { null }
                places += NearbyPlace(name, pLat, pLng, Geo.distanceMeters(lat, lng, pLat, pLng), detail)
            }
            return places
        }
    }
}
