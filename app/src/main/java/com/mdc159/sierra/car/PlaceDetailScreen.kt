package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.mdc159.sierra.places.NearbyPlace
import java.util.Locale

/** One place, with a Navigate action that hands off to Google Maps. */
class PlaceDetailScreen(
    carContext: CarContext,
    private val place: NearbyPlace,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val miles = place.distanceMeters / 1609.344
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(place.name)
                    .addText(String.format(Locale.US, "%.1f mi away", miles))
                    .build()
            )
        place.detail?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        pane.addAction(
            Action.Builder()
                .setTitle("Navigate")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { NavHandoff.toPlace(carContext, place.lat, place.lng, place.name) }
                .build()
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle(place.name)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
