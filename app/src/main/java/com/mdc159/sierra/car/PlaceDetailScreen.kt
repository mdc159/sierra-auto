package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.mdc159.sierra.places.NearbyPlace
import com.mdc159.sierra.places.displayDetail
import com.mdc159.sierra.places.displayName
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
                    .setTitle(place.displayName)
                    .addText(String.format(Locale.US, "%.1f mi away", miles))
                    .build()
            )
        // An empty row title is rejected by the host, so a blank detail gets no row.
        place.displayDetail?.let { pane.addRow(Row.Builder().setTitle(it).build()) }
        pane.addAction(
            Action.Builder()
                .setTitle("Navigate")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { NavHandoff.toPlace(carContext, place.lat, place.lng, place.name) }
                .build()
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle(place.displayName)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
