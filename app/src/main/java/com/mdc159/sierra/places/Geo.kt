package com.mdc159.sierra.places

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** A bounding box "left,top,right,bottom" (lng,lat,lng,lat) around a point. */
    fun viewbox(lat: Double, lng: Double, radiusMeters: Int): String {
        val dLat = radiusMeters / METERS_PER_DEGREE_LAT
        val dLng = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", lng - dLng, lat + dLat, lng + dLng, lat - dLat)
    }

    /**
     * Drops entries with the same name at (near) the same spot, keeping the nearest one, and
     * sorts by distance. Sources do not order their results, so sort before deduplicating.
     */
    fun dedupeAndSort(places: List<NearbyPlace>): List<NearbyPlace> =
        places
            .sortedBy { it.distanceMeters }
            .distinctBy { String.format(Locale.US, "%s|%.4f|%.4f", it.name, it.lat, it.lng) }
}
