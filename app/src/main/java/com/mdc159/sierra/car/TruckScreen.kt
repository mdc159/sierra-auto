package com.mdc159.sierra.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.obd.bt.BluetoothPermissions
import com.mdc159.sierra.obd.live.ObdSettings
import com.mdc159.sierra.obd.live.ObdSettingsStore
import com.mdc159.sierra.obd.live.ObdTransports
import com.mdc159.sierra.obd.live.TruckFormat
import com.mdc159.sierra.obd.live.TruckMonitor
import com.mdc159.sierra.obd.live.TruckReadings
import com.mdc159.sierra.obd.live.TruckState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live engine data on a PaneTemplate, which is allowed while driving.
 *
 * The pane keeps four rows with fixed titles; only their secondary text changes, so
 * each update is a refresh of the same template rather than a new task step. Updates
 * are rate limited to one every [MIN_REFRESH_MILLIS] regardless of how fast the
 * adapter answers. This screen owns the adapter connection: it starts the
 * [TruckMonitor] when created and closes it when destroyed, so the socket never
 * outlives the screen.
 */
class TruckScreen(
    carContext: CarContext,
    private val settings: ObdSettings = ObdSettingsStore.load(carContext),
    private val monitorFactory: () -> TruckMonitor = {
        TruckMonitor({ pending -> ObdTransports.open(carContext, settings, pending) })
    },
) : Screen(carContext) {

    private var monitor: TruckMonitor? = null
    private var needsPermission = !settings.isDemo && !BluetoothPermissions.hasConnect(carContext)
    private val title = if (settings.isDemo) "Truck (demo)" else "Truck"

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                monitor?.close()
                monitor = null
            }
        })
        if (!needsPermission) startMonitor()
    }

    private fun startMonitor() {
        val m = monitorFactory().also { monitor = it }
        m.start()
        lifecycleScope.launch {
            // StateFlow conflates: after each delay the collector sees only the latest state.
            m.state.collect {
                invalidate()
                delay(MIN_REFRESH_MILLIS)
            }
        }
    }

    private fun requestBluetooth() {
        carContext.requestPermissions(BluetoothPermissions.connect()) { granted, _ ->
            if (granted.isNotEmpty() || BluetoothPermissions.hasConnect(carContext)) {
                needsPermission = false
                startMonitor()
            }
            invalidate()
        }
    }

    /** A fresh screen restarts the connection and hands the popped template steps back. */
    private fun retry() {
        screenManager.pop()
        screenManager.push(TruckScreen(carContext))
    }

    override fun onGetTemplate(): Template {
        if (needsPermission) return permissionTemplate(title) { requestBluetooth() }
        val m = monitor
        return when (val s = m?.state?.value ?: TruckState.Connecting()) {
            is TruckState.Connecting -> loadingTemplate(title)
            is TruckState.Live -> liveTemplate(title, s.readings) {
                if (m != null) screenManager.push(TruckCodesScreen(carContext, m))
            }
            is TruckState.Failed -> failedTemplate(title, s.message) { retry() }
        }
    }

    companion object {
        /** Screen updates are no more frequent than this, whatever the adapter's pace. */
        const val MIN_REFRESH_MILLIS = 1_000L

        fun loadingTemplate(title: String): Template =
            PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()

        /** Four fixed-title rows (the pane limit) carrying seven values in their text lines. */
        fun liveTemplate(title: String, r: TruckReadings, onCodes: () -> Unit): Template {
            val pane = Pane.Builder()
                .addRow(
                    Row.Builder().setTitle("Engine")
                        .addText(TruckFormat.rpm(r.rpm))
                        .addText("Load " + TruckFormat.percent(r.loadPct))
                        .build()
                )
                .addRow(Row.Builder().setTitle("Speed").addText(TruckFormat.mph(r.speedKmh)).build())
                .addRow(
                    Row.Builder().setTitle("Temperature")
                        .addText("Coolant " + TruckFormat.fahrenheit(r.coolantC))
                        .addText("Intake air " + TruckFormat.fahrenheit(r.intakeC))
                        .build()
                )
                .addRow(
                    Row.Builder().setTitle("Fuel and battery")
                        .addText("Fuel " + TruckFormat.percent(r.fuelPct))
                        .addText(TruckFormat.volts(r.voltage))
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Codes")
                        .setFlags(Action.FLAG_PRIMARY)
                        .setOnClickListener(onCodes)
                        .build()
                )
                .build()
            return PaneTemplate.Builder(pane)
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()
        }

        fun failedTemplate(title: String, message: String, onRetry: () -> Unit): Template =
            MessageTemplate.Builder(message.take(120))
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .setIcon(CarIcon.ALERT)
                .addAction(
                    Action.Builder()
                        .setTitle("Retry")
                        .setFlags(Action.FLAG_PRIMARY)
                        .setOnClickListener(onRetry)
                        .build()
                )
                .build()

        fun permissionTemplate(title: String, onAllow: () -> Unit): Template =
            MessageTemplate.Builder("Allow Bluetooth for Sierra on your phone when it's safe.")
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle("Allow")
                        .setFlags(Action.FLAG_PRIMARY)
                        .setOnClickListener(onAllow)
                        .build()
                )
                .build()
    }
}
