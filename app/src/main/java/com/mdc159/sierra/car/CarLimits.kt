package com.mdc159.sierra.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.versioning.CarAppApiLevels

/** Host-reported list limits, with conservative defaults for old hosts. */
object CarLimits {
    fun listLimit(carContext: CarContext, type: Int = ConstraintManager.CONTENT_LIMIT_TYPE_LIST): Int {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_2) return defaultLimit(type)
        return runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(type)
        }.getOrDefault(defaultLimit(type)).coerceAtLeast(1)
    }

    /**
     * The library's documented minimums (car/app/app/src/main/res/values/integers.xml), used
     * when the host cannot be asked. A pane shows at most 4 rows, not 6.
     */
    fun defaultLimit(type: Int): Int = when (type) {
        ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST -> 3
        ConstraintManager.CONTENT_LIMIT_TYPE_PANE -> 4
        else -> 6
    }

    fun placeListLimit(carContext: CarContext): Int =
        listLimit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
}

/** Hands navigation off to the default navigation app (Google Maps). */
object NavHandoff {
    fun toPlace(carContext: CarContext, lat: Double, lng: Double, name: String) {
        val label = name.replace("(", " ").replace(")", " ")
        start(carContext, "geo:0,0?q=$lat,$lng(${Uri.encode(label)})")
    }

    fun search(carContext: CarContext, query: String) {
        start(carContext, "geo:0,0?q=${Uri.encode(query)}")
    }

    private fun start(carContext: CarContext, uri: String) {
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri)))
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Couldn't open navigation", CarToast.LENGTH_LONG).show()
        }
    }
}
