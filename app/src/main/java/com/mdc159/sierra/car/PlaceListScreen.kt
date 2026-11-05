package com.mdc159.sierra.car

import android.Manifest
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.places.NearbyPlace
import com.mdc159.sierra.places.NearbyPlaces
import com.mdc159.sierra.places.PhoneLocation
import com.mdc159.sierra.places.PlaceCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host-drawn map plus a list of nearby places in one category.
 * The template stays a PlaceListMapTemplate in every state so refreshes don't use up
 * template steps; errors appear as rows with a fallback action.
 */
class PlaceListScreen(
    carContext: CarContext,
    private val category: PlaceCategory,
    private val placeSource: NearbyPlaces = NearbyPlaces(),
) : Screen(carContext) {

    private sealed interface State {
        data object Loading : State
        data object NeedsPermission : State
        data class Failed(val message: String) : State
        data class Loaded(val lat: Double, val lng: Double, val places: List<NearbyPlace>) : State
    }

    private var state: State = State.Loading
    private var job: Job? = null

    init {
        load(initial = true)
    }

    private fun load(initial: Boolean = false) {
        if (!PhoneLocation.hasPermission(carContext)) {
            state = State.NeedsPermission
            if (!initial) invalidate()
            return
        }
        state = State.Loading
        if (!initial) invalidate()
        job?.cancel()
        job = lifecycleScope.launch {
            val location = PhoneLocation.current(carContext)
            state = if (location == null) {
                State.Failed("No location fix yet.")
            } else {
                val limit = CarLimits.placeListLimit(carContext)
                try {
                    val places = withContext(Dispatchers.IO) {
                        placeSource.search(category, location.latitude, location.longitude, limit)
                    }
                    State.Loaded(location.latitude, location.longitude, places)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    State.Failed(Http.describe(e))
                }
            }
            invalidate()
        }
    }

    private fun requestPermission() {
        carContext.requestPermissions(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) { granted, _ ->
            if (granted.isNotEmpty()) load() else invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = PlaceListMapTemplate.Builder()
            .setTitle(category.label)
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("Refresh").setOnClickListener { load() }.build())
                    .build()
            )

        when (val s = state) {
            State.Loading -> return builder.setLoading(true).build()
            State.NeedsPermission -> builder.setItemList(
                ItemList.Builder()
                    .addItem(
                        Row.Builder()
                            .setTitle("Allow location on your phone")
                            .addText("Sierra needs your location to find places.")
                            .setOnClickListener { requestPermission() }
                            .build()
                    )
                    .addItem(mapsSearchRow())
                    .build()
            )
            is State.Failed -> builder.setItemList(
                ItemList.Builder()
                    .addItem(Row.Builder().setTitle("Couldn't load places").addText(s.message).build())
                    .addItem(mapsSearchRow())
                    .build()
            )
            is State.Loaded -> {
                builder.setCurrentLocationEnabled(PhoneLocation.hasPermission(carContext))
                builder.setAnchor(Place.Builder(CarLocation.create(s.lat, s.lng)).build())
                val items = ItemList.Builder()
                    .setNoItemsMessage("No ${category.label.lowercase()} found nearby.")
                s.places.forEach { place -> items.addItem(placeRow(place)) }
                builder.setItemList(items.build())
            }
        }
        return builder.build()
    }

    private fun placeRow(place: NearbyPlace): Row {
        val distance = SpannableString(" ").apply {
            setSpan(
                DistanceSpan.create(Distance.create(place.distanceMeters / 1609.344, Distance.UNIT_MILES)),
                0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        val row = Row.Builder()
            .setTitle(place.name)
            .addText(distance)
            .setMetadata(
                Metadata.Builder()
                    .setPlace(
                        Place.Builder(CarLocation.create(place.lat, place.lng))
                            .setMarker(PlaceMarker.Builder().build())
                            .build()
                    )
                    .build()
            )
            .setOnClickListener { screenManager.push(PlaceDetailScreen(carContext, place)) }
        place.detail?.let { row.addText(it) }
        return row.build()
    }

    private fun mapsSearchRow(): Row =
        Row.Builder()
            .setTitle("Search \"${category.mapsQuery}\" in Maps")
            .setOnClickListener { NavHandoff.search(carContext, category.mapsQuery) }
            .build()
}
