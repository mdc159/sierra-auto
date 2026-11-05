package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/** Stand-in for features that land in later milestones. */
class PlaceholderScreen(
    carContext: CarContext,
    private val name: String,
) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("$name is coming soon.")
            .setTitle(name)
            .setHeaderAction(Action.BACK)
            .build()
}
