package com.mdc159.sierra.car

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.places.NearbyPlace
import com.mdc159.sierra.places.NearbyPlaces
import com.mdc159.sierra.places.PhoneLocation
import com.mdc159.sierra.places.PlaceCategory
import com.mdc159.sierra.places.displayDetail
import com.mdc159.sierra.places.displayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

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
        // A refresh of a loaded list keeps the rows on screen until new ones arrive, so the
        // host sees a content refresh rather than a new loading template.
        if (state !is State.Loaded) {
            state = State.Loading
            if (!initial) invalidate()
        }
        job?.cancel()
        job = lifecycleScope.launch {
            val location = PhoneLocation.current(carContext)
            state = if (location == null) {
                State.Failed("No location fix yet.")
            } else {
                val limit = CarLimits.placeListLimit(carContext)
                try {
                    // runInterruptible lets cancellation interrupt the blocking HTTP calls.
                    val places = runInterruptible(Dispatchers.IO) {
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
        val onRefresh = { load() }
        val hostRefresh = carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5
        when (val s = state) {
            State.Loading -> return baseBuilder(category.label, onRefresh, hostRefresh).setLoading(true).build()
            State.NeedsPermission -> return statusTemplate(
                category.label,
                listOf(
                    StatusRow("Allow location on your phone", "Sierra needs it to find places.") {
                        requestPermission()
                    },
                    mapsSearchRow(),
                ),
                onRefresh,
                hostRefresh,
            )
            is State.Failed -> return statusTemplate(
                category.label,
                listOf(StatusRow("Couldn't load places. Tap to retry.", s.message) { load() }, mapsSearchRow()),
                onRefresh,
                hostRefresh,
            )
            is State.Loaded -> {
                val builder = baseBuilder(category.label, onRefresh, hostRefresh)
                // setCurrentLocationEnabled requires a location permission (lint checks it here).
                if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    builder.setCurrentLocationEnabled(true)
                }
                builder.setAnchor(Place.Builder(CarLocation.create(s.lat, s.lng)).build())
                val items = ItemList.Builder()
                    .setNoItemsMessage("No ${category.label.lowercase()} found nearby.")
                // The host ignores rows past its limit; never send them even if a source does.
                s.places.take(CarLimits.placeListLimit(carContext)).forEach { place -> items.addItem(placeRow(place)) }
                builder.setItemList(items.build())
                return builder.build()
            }
        }
    }

    private fun placeRow(place: NearbyPlace): Row {
        val distance = SpannableString(" ").apply {
            setSpan(
                DistanceSpan.create(Distance.create(place.distanceMeters / 1609.344, Distance.UNIT_MILES)),
                0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        val row = Row.Builder()
            .setTitle(place.displayName)
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
        place.displayDetail?.let { row.addText(it) }
        return row.build()
    }

    private fun mapsSearchRow() = StatusRow("Search \"${category.mapsQuery}\" in Maps", null) {
        NavHandoff.search(carContext, category.mapsQuery)
    }

    /** A non-place row. It must be browsable: PlaceListMapTemplate requires every
     * non-browsable row to carry a DistanceSpan. */
    class StatusRow(val title: String, val text: String?, val onClick: () -> Unit)

    companion object {
        /**
         * On Car API 5+ the host's own refresh control is used (it does not cost a template
         * step); older hosts get a Refresh action in the strip.
         */
        fun baseBuilder(
            title: String,
            onRefresh: () -> Unit,
            hostRefresh: Boolean = false,
        ): PlaceListMapTemplate.Builder {
            val builder = PlaceListMapTemplate.Builder()
                .setTitle(title)
                .setHeaderAction(Action.BACK)
            if (hostRefresh) {
                builder.setOnContentRefreshListener(onRefresh)
            } else {
                builder.setActionStrip(
                    ActionStrip.Builder()
                        .addAction(Action.Builder().setTitle("Refresh").setOnClickListener(onRefresh).build())
                        .build()
                )
            }
            return builder
        }

        fun statusTemplate(
            title: String,
            rows: List<StatusRow>,
            onRefresh: () -> Unit,
            hostRefresh: Boolean = false,
        ): Template {
            val items = ItemList.Builder()
            rows.forEach { r ->
                val row = Row.Builder().setTitle(r.title).setBrowsable(true).setOnClickListener(r.onClick)
                r.text?.let { row.addText(it) }
                items.addItem(row.build())
            }
            return baseBuilder(title, onRefresh, hostRefresh).setItemList(items.build()).build()
        }
    }
}
