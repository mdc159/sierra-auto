package com.mdc159.sierra

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.obd.Elm327Client
import com.mdc159.sierra.obd.bt.BluetoothLeTransport
import com.mdc159.sierra.obd.bt.BluetoothPermissions
import com.mdc159.sierra.obd.live.AdapterKind
import com.mdc159.sierra.obd.live.ObdProtocols
import com.mdc159.sierra.obd.live.ObdSettings
import com.mdc159.sierra.obd.live.ObdSettingsStore
import com.mdc159.sierra.obd.live.ObdTransports
import com.mdc159.sierra.obd.live.TruckFormat
import com.mdc159.sierra.obd.live.TruckMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-side OBD-II settings: Bluetooth permissions, which adapter (or demo mode) the
 * Truck screen uses, a BLE scan for adapters that cannot be paired, and a connection
 * test that runs the ELM327 init and reports protocol and battery voltage.
 */
class ObdSettingsActivity : ComponentActivity() {

    private lateinit var permissionStatus: TextView
    private lateinit var adapterGroup: RadioGroup
    private lateinit var scanButton: Button
    private lateinit var testButton: Button
    private lateinit var testResult: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val scanned = LinkedHashMap<String, String>() // address -> name
    private var scanCallback: ScanCallback? = null
    private var selected: ObdSettings = ObdSettings.DEMO

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun heading(text: String) = form.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })
        fun note(text: String) = form.addView(TextView(this).apply { this.text = text })

        form.addView(TextView(this).apply {
            text = "Truck data (OBD-II)"
            textSize = 24f
        })
        note(
            "The Truck screen in Android Auto reads live engine data and stored trouble codes " +
                "through an ELM327-class Bluetooth adapter in the OBD-II port. Sierra only reads; " +
                "it never clears codes or changes anything in the truck."
        )

        heading("Bluetooth permissions")
        permissionStatus = TextView(this)
        form.addView(permissionStatus)
        form.addView(Button(this).apply {
            text = "Grant Bluetooth permissions"
            setOnClickListener { requestPermissions() }
        })

        heading("Adapter")
        note(
            "Demo mode needs no hardware. For a Bluetooth classic adapter, pair it in the phone's " +
                "Bluetooth settings first (PIN usually 1234 or 0000), then pick it here. Bluetooth LE " +
                "adapters usually can't be paired; scan for them instead. BLE support is unverified."
        )
        adapterGroup = RadioGroup(this)
        form.addView(adapterGroup)
        scanButton = Button(this).apply {
            text = SCAN_LABEL
            setOnClickListener { scan() }
        }
        form.addView(scanButton)

        heading("Test")
        testButton = Button(this).apply {
            text = "Test adapter"
            setOnClickListener { testAdapter() }
        }
        form.addView(testButton)
        testResult = TextView(this)
        form.addView(testResult)

        setContentView(ScrollView(this).apply { addView(form) })
        selected = ObdSettingsStore.load(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        stopScan()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun requestPermissions() {
        val missing = BluetoothPermissions.missing(this)
        if (missing.isEmpty()) {
            Toast.makeText(this, "Bluetooth permissions already granted.", Toast.LENGTH_SHORT).show()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun refresh() {
        if (!::permissionStatus.isInitialized) return
        val missing = BluetoothPermissions.missing(this)
        permissionStatus.text = when {
            BluetoothPermissions.all().isEmpty() -> "No runtime Bluetooth permission needed on this Android version."
            missing.isEmpty() -> "Bluetooth permissions granted."
            else -> "Missing: " + missing.joinToString { it.substringAfterLast('.') }
        }
        rebuildAdapterList()
    }

    private fun rebuildAdapterList() {
        adapterGroup.setOnCheckedChangeListener(null)
        adapterGroup.removeAllViews()
        val entries = LinkedHashMap<String, Pair<ObdSettings, String>>()
        fun add(s: ObdSettings, label: String) {
            entries.putIfAbsent("${s.kind}:${s.address.uppercase()}", s to label)
        }
        add(ObdSettings.DEMO, "Demo mode: simulated engine, two stored codes (no adapter)")
        bondedEntries().forEach { s ->
            val kindLabel = if (s.kind == AdapterKind.BLE) "Bluetooth LE" else "Bluetooth classic"
            add(s, "${s.name}  ($kindLabel, ${s.address})")
        }
        scanned.forEach { (address, name) ->
            add(ObdSettings(AdapterKind.BLE, address, name), "$name  (Bluetooth LE, found by scan, $address)")
        }
        if (!selected.isDemo) add(selected, "${selected.label}  (saved, not currently visible: ${selected.address})")

        var checkedId = View.NO_ID
        entries.values.forEach { (s, label) ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                tag = s
            }
            adapterGroup.addView(button)
            if (s.kind == selected.kind && s.address.equals(selected.address, ignoreCase = true)) checkedId = button.id
        }
        if (checkedId != View.NO_ID) adapterGroup.check(checkedId)
        adapterGroup.setOnCheckedChangeListener { group, id ->
            val s = group.findViewById<RadioButton>(id)?.tag as? ObdSettings ?: return@setOnCheckedChangeListener
            if (s != selected) {
                selected = s
                ObdSettingsStore.save(this, s)
                Toast.makeText(this, "Truck screen will use ${s.label}.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Paired devices, one entry per transport a device supports. Needs BLUETOOTH_CONNECT on API 31+. */
    @SuppressLint("MissingPermission")
    private fun bondedEntries(): List<ObdSettings> {
        if (!BluetoothPermissions.hasConnect(this)) return emptyList()
        val adapter = BluetoothPermissions.adapter(this) ?: return emptyList()
        val devices = runCatching { adapter.bondedDevices }.getOrNull() ?: return emptyList()
        return devices.flatMap { d ->
            val name = runCatching { d.name }.getOrNull() ?: d.address
            when (runCatching { d.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)) {
                BluetoothDevice.DEVICE_TYPE_LE -> listOf(ObdSettings(AdapterKind.BLE, d.address, name))
                BluetoothDevice.DEVICE_TYPE_DUAL -> listOf(
                    ObdSettings(AdapterKind.CLASSIC, d.address, name),
                    ObdSettings(AdapterKind.BLE, d.address, name),
                )
                else -> listOf(ObdSettings(AdapterKind.CLASSIC, d.address, name))
            }
        }.sortedBy { it.name.lowercase() }
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        if (!BluetoothPermissions.hasScan(this) || !BluetoothPermissions.hasConnect(this)) {
            requestPermissions()
            return
        }
        val adapter = BluetoothPermissions.adapter(this)
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Turn on Bluetooth first.", Toast.LENGTH_SHORT).show()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth LE scanning isn't available.", Toast.LENGTH_SHORT).show()
            return
        }
        stopScan()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: return
                if (scanned.put(device.address, name) != name) rebuildAdapterList()
            }

            override fun onScanFailed(errorCode: Int) {
                Toast.makeText(this@ObdSettingsActivity, "Scan failed (error $errorCode).", Toast.LENGTH_SHORT).show()
                stopScan()
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (e: Exception) {
            scanCallback = null
            Toast.makeText(this, "Couldn't start the scan: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        scanButton.isEnabled = false
        scanButton.text = "Scanning for ${SCAN_MILLIS / 1000} s…"
        handler.postDelayed({ stopScan() }, SCAN_MILLIS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { BluetoothPermissions.adapter(this)?.bluetoothLeScanner?.stopScan(callback) }
        if (::scanButton.isInitialized) {
            scanButton.isEnabled = true
            scanButton.text = SCAN_LABEL
        }
    }

    /** Connects, runs the init sequence, reads the protocol and `ATRV`, then disconnects. */
    private fun testAdapter() {
        val settings = selected
        if (!settings.isDemo && !BluetoothPermissions.hasConnect(this)) {
            requestPermissions()
            return
        }
        stopScan()
        testButton.isEnabled = false
        testResult.text = "Connecting to ${settings.label}…"
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ObdTransports.open(this@ObdSettingsActivity, settings).use { transport ->
                        val client = Elm327Client(transport)
                        val info = client.initialize()
                        val volts = runCatching { client.readBatteryVoltage() }.getOrNull()
                        val layout = (transport as? BluetoothLeTransport)?.layoutName
                        buildString {
                            append("Adapter: ").append(info.identity.ifBlank { "(no banner)" }).append('\n')
                            append("Protocol: ").append(ObdProtocols.describe(info.protocol)).append('\n')
                            append("Battery: ").append(TruckFormat.volts(volts)).append('\n')
                            if (layout != null) append("BLE layout: ").append(layout).append('\n')
                            append("Bus answered PID 0100: yes")
                        }
                    }
                }
            }
            testResult.text = outcome.getOrElse { "Failed: " + TruckMonitor.describe(it) }
            testButton.isEnabled = true
        }
    }

    companion object {
        private const val SCAN_MILLIS = 8_000L
        private const val SCAN_LABEL = "Scan for Bluetooth LE adapters"
    }
}
