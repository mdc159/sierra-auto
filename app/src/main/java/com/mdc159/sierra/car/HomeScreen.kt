package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/** Landing screen: a greeting plus the two entry points, Places and Talk. */
class HomeScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val places = Action.Builder()
            .setTitle("Places")
            .setOnClickListener { screenManager.push(PlaceCategoriesScreen(carContext)) }
            .build()
        val talk = Action.Builder()
            .setTitle("Talk")
            .setOnClickListener { screenManager.push(PlaceholderScreen(carContext, "Talk")) }
            .build()

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Sierra — hello, truck")
                    .addText("Your agent, on the dash.")
                    .build()
            )
            .addAction(places)
            .addAction(talk)
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Sierra")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(places).addAction(talk).build())
            .build()
    }
}
