package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.mdc159.sierra.places.PlaceCategory

/** Picks what to look for nearby. */
class PlaceCategoriesScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        PlaceCategory.entries.take(CarLimits.listLimit(carContext)).forEach { category ->
            items.addItem(
                Row.Builder()
                    .setTitle(category.label)
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(PlaceListScreen(carContext, category)) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Places nearby")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
