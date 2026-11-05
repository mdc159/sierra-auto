package com.mdc159.sierra.places

import com.mdc159.sierra.net.Http
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keyless nearby search against OpenStreetMap's Overpass API.
 * Tries each endpoint in turn; throws [IOException] with a readable message if all fail.
 */
class OverpassClient(
    private val client: OkHttpClient = Http.client.newBuilder()
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) : PlaceSource {

    override fun search(category: PlaceCategory, lat: Double, lng: Double, limit: Int): List<NearbyPlace> {
        val query = buildQuery(category, lat, lng, category.radiusMeters)
        var lastError: IOException? = null
        for (endpoint in endpoints) {
            try {
                val body = post(endpoint, query)
                return parse(body, category, lat, lng).take(limit)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw IOException(Http.describe(lastError ?: IOException("no endpoint")), lastError)
    }

    private fun post(endpoint: String, query: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", Http.USER_AGENT)
            .post(FormBody.Builder().add("data", query).build())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Places server busy (${response.code}).")
            return response.body?.string() ?: throw IOException("Empty places response.")
        }
    }

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        fun buildQuery(category: PlaceCategory, lat: Double, lng: Double, radiusMeters: Int): String {
            val around = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusMeters, lat, lng)
            val clauses = category.overpassFilters.joinToString("") { "nwr$it$around;" }
            return "[out:json][timeout:8];($clauses);out center 60;"
        }

        fun parse(json: String, category: PlaceCategory, lat: Double, lng: Double): List<NearbyPlace> {
            val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
            val places = ArrayList<NearbyPlace>(elements.length())
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val center = el.optJSONObject("center")
                val pLat = if (el.has("lat")) el.optDouble("lat") else center?.optDouble("lat") ?: Double.NaN
                val pLng = if (el.has("lon")) el.optDouble("lon") else center?.optDouble("lon") ?: Double.NaN
                if (pLat.isNaN() || pLng.isNaN()) continue
                val tags = el.optJSONObject("tags")
                fun tag(key: String): String? = tags?.optString(key)?.ifBlank { null }
                val brand = tag("brand")
                val name = tag("name") ?: brand ?: category.label
                val street = listOfNotNull(tag("addr:housenumber"), tag("addr:street"))
                    .joinToString(" ").ifBlank { null }
                val detail = listOfNotNull(street, tag("addr:city")).joinToString(", ").ifBlank {
                    if (brand != null && brand != name) brand else null
                }
                places += NearbyPlace(name, pLat, pLng, Geo.distanceMeters(lat, lng, pLat, pLng), detail)
            }
            return Geo.dedupeAndSort(places)
        }
    }
}
