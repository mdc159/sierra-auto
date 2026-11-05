package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mdc159.sierra.R
import com.mdc159.sierra.agent.ConfigStore

/** Landing screen: a greeting, setup status, and the entry points Talk, Places, History. */
class HomeScreen(
    carContext: CarContext,
    private val session: SierraSession,
) : Screen(carContext) {

    init {
        // Settings may change on the phone while the car screen is up.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = invalidate()
        })
    }

    override fun onGetTemplate(): Template = template(
        configured = runCatching { ConfigStore.load(carContext).isConfigured }.getOrDefault(false),
        onPlaces = { screenManager.push(PlaceCategoriesScreen(carContext)) },
        onTalk = { screenManager.push(TalkScreen(carContext, session)) },
        onHistory = { screenManager.push(HistoryScreen(carContext, session)) },
        placesIcon = icon(R.drawable.ic_place),
        talkIcon = icon(R.drawable.ic_mic),
    )

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    companion object {
        /**
         * PaneTemplate allows two pane actions and an action strip with at most two
         * actions, only one of which may have a title.
         */
        fun template(
            configured: Boolean,
            onPlaces: () -> Unit,
            onTalk: () -> Unit,
            onHistory: () -> Unit,
            placesIcon: CarIcon,
            talkIcon: CarIcon,
        ): Template {
            val status = if (configured) {
                Row.Builder().setTitle("Sierra — hello, truck").addText("Tap Talk and ask anything.")
            } else {
                Row.Builder().setTitle("Set up your agent on the phone")
                    .addText("Open Sierra on the phone and enter an endpoint and model.")
            }
            val pane = Pane.Builder()
                .addRow(status.build())
                .addAction(
                    Action.Builder()
                        .setTitle("Talk")
                        .setIcon(talkIcon)
                        .setFlags(Action.FLAG_PRIMARY)
                        .setOnClickListener(onTalk)
                        .build()
                )
                .addAction(Action.Builder().setTitle("Places").setIcon(placesIcon).setOnClickListener(onPlaces).build())
                .build()

            val strip = ActionStrip.Builder()
                .addAction(Action.Builder().setIcon(placesIcon).setOnClickListener(onPlaces).build())
                .addAction(Action.Builder().setTitle("History").setOnClickListener(onHistory).build())
                .build()

            return PaneTemplate.Builder(pane)
                .setTitle("Sierra")
                .setHeaderAction(Action.APP_ICON)
                .setActionStrip(strip)
                .build()
        }
    }
}
