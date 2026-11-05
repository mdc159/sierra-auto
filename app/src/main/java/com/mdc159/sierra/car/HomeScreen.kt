package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mdc159.sierra.R
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.obd.live.ObdSettingsStore

/**
 * Landing hub: Talk, Places, Truck and History as a four-item grid. GridTemplate is
 * allowed while driving and the host's grid limit is never below six items. Setup
 * guidance lives in the items' secondary text so the grid stays the only content.
 */
class HomeScreen(
    carContext: CarContext,
    private val session: SierraSession,
) : Screen(carContext) {

    /** One hub entry. The screen builds the real [GridItem] from it. */
    class Entry(val title: String, val text: String, val icon: CarIcon, val onClick: () -> Unit)

    init {
        // Settings may change on the phone while the car screen is up.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = invalidate()
        })
    }

    override fun onGetTemplate(): Template {
        val configured = runCatching { ConfigStore.load(carContext).isConfigured }.getOrDefault(false)
        val demoTruck = runCatching { ObdSettingsStore.load(carContext).isDemo }.getOrDefault(true)
        return template(
            entries(
                configured = configured,
                demoTruck = demoTruck,
                onTalk = { screenManager.push(TalkScreen(carContext, session)) },
                onPlaces = { screenManager.push(PlaceCategoriesScreen(carContext)) },
                onTruck = { screenManager.push(TruckScreen(carContext)) },
                onHistory = { screenManager.push(HistoryScreen(carContext, session)) },
                talkIcon = icon(R.drawable.ic_mic),
                placesIcon = icon(R.drawable.ic_place),
                truckIcon = icon(R.drawable.ic_truck),
                historyIcon = icon(R.drawable.ic_history),
            ),
            limit = CarLimits.listLimit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_GRID),
        )
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    companion object {
        fun entries(
            configured: Boolean,
            demoTruck: Boolean,
            onTalk: () -> Unit,
            onPlaces: () -> Unit,
            onTruck: () -> Unit,
            onHistory: () -> Unit,
            talkIcon: CarIcon,
            placesIcon: CarIcon,
            truckIcon: CarIcon,
            historyIcon: CarIcon,
        ): List<Entry> = listOf(
            Entry("Talk", if (configured) "Ask anything" else "Set up on the phone first", talkIcon, onTalk),
            Entry("Places", "Fuel, food, rest, parking", placesIcon, onPlaces),
            Entry("Truck", if (demoTruck) "Live data (demo)" else "Live data", truckIcon, onTruck),
            Entry("History", "Recent questions", historyIcon, onHistory),
        )

        /** Builds the grid, never exceeding the host's grid item [limit]. */
        fun template(entries: List<Entry>, limit: Int): Template {
            val items = ItemList.Builder()
            entries.take(limit.coerceAtLeast(1)).forEach { e ->
                items.addItem(
                    GridItem.Builder()
                        .setTitle(e.title)
                        .setText(e.text)
                        .setImage(e.icon, GridItem.IMAGE_TYPE_ICON)
                        .setOnClickListener(e.onClick)
                        .build()
                )
            }
            return GridTemplate.Builder()
                .setTitle("Sierra")
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(items.build())
                .build()
        }
    }
}
