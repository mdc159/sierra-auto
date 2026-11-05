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
import com.mdc159.sierra.R

/** Landing screen: a greeting plus the two entry points, Places and Talk. */
class HomeScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template = template(
        onPlaces = { screenManager.push(PlaceCategoriesScreen(carContext)) },
        onTalk = { screenManager.push(PlaceholderScreen(carContext, "Talk")) },
        placesIcon = icon(R.drawable.ic_place),
        talkIcon = icon(R.drawable.ic_mic),
    )

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    companion object {
        /**
         * PaneTemplate's action strip allows at most one titled action, so the strip uses
         * icon-only actions and the pane carries the titled buttons.
         */
        fun template(
            onPlaces: () -> Unit,
            onTalk: () -> Unit,
            placesIcon: CarIcon,
            talkIcon: CarIcon,
        ): Template {
            val pane = Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("Sierra — hello, truck")
                        .addText("Your agent, on the dash.")
                        .build()
                )
                .addAction(Action.Builder().setTitle("Places").setOnClickListener(onPlaces).build())
                .addAction(Action.Builder().setTitle("Talk").setOnClickListener(onTalk).build())
                .build()

            val strip = ActionStrip.Builder()
                .addAction(Action.Builder().setIcon(placesIcon).setOnClickListener(onPlaces).build())
                .addAction(Action.Builder().setIcon(talkIcon).setOnClickListener(onTalk).build())
                .build()

            return PaneTemplate.Builder(pane)
                .setTitle("Sierra")
                .setHeaderAction(Action.APP_ICON)
                .setActionStrip(strip)
                .build()
        }
    }
}
