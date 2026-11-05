package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.obd.live.CodesState
import com.mdc159.sierra.obd.live.TruckMonitor
import kotlinx.coroutines.launch

/**
 * Stored (confirmed) trouble codes, read-only. Clearing codes is deliberately absent:
 * see docs/OBD2-PLAN.md section 6. Uses the connection the Truck screen owns.
 */
class TruckCodesScreen(
    carContext: CarContext,
    private val monitor: TruckMonitor,
) : Screen(carContext) {

    init {
        monitor.requestCodes()
        lifecycleScope.launch { monitor.codes.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template =
        template(monitor.codes.value, CarLimits.listLimit(carContext)) { monitor.requestCodes() }

    companion object {
        fun template(state: CodesState, rowLimit: Int, onRetry: () -> Unit): Template {
            val builder = ListTemplate.Builder()
                .setTitle("Codes")
                .setHeaderAction(Action.BACK)
            return when (state) {
                CodesState.Idle, CodesState.Loading -> builder.setLoading(true).build()
                is CodesState.Failed -> {
                    builder.setSingleList(
                        ItemList.Builder()
                            .addItem(Row.Builder().setTitle("Couldn't read codes").addText(state.message.take(120)).build())
                            .build()
                    )
                    builder.setActionStrip(
                        ActionStrip.Builder()
                            .addAction(Action.Builder().setTitle("Retry").setOnClickListener(onRetry).build())
                            .build()
                    )
                    builder.build()
                }
                is CodesState.Loaded -> {
                    val rows = mutableListOf<Row>()
                    // The last row is always the read-only note; everything else shares the rest.
                    val budget = rowLimit.coerceAtLeast(2) - 1
                    val status = state.status
                    if (status != null && budget >= 2) {
                        rows += Row.Builder()
                            .setTitle(if (status.milOn) "Check engine light is on" else "Check engine light is off")
                            .addText("${status.dtcCount} stored code" + if (status.dtcCount == 1) "" else "s")
                            .build()
                    }
                    val room = budget - rows.size
                    val stored = state.stored
                    if (stored.isEmpty()) {
                        rows += Row.Builder().setTitle("No stored codes").build()
                    } else {
                        val shown = if (stored.size <= room) stored.size else (room - 1).coerceAtLeast(0)
                        stored.take(shown).forEach { code ->
                            rows += Row.Builder().setTitle(code.code).addText("Stored (confirmed) code").build()
                        }
                        val hidden = stored.size - shown
                        if (hidden > 0) {
                            rows += Row.Builder()
                                .setTitle("+$hidden more")
                                .addText(stored.drop(shown).joinToString(" ").take(120))
                                .build()
                        }
                    }
                    rows += Row.Builder().setTitle("Read-only").addText("Sierra never clears codes. Use a scan tool.").build()
                    val items = ItemList.Builder()
                    rows.forEach { items.addItem(it) }
                    builder.setSingleList(items.build())
                    builder.setActionStrip(
                        ActionStrip.Builder()
                            .addAction(Action.Builder().setTitle("Refresh").setOnClickListener(onRetry).build())
                            .build()
                    )
                    builder.build()
                }
            }
        }
    }
}
