package com.mdc159.sierra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Phone-side screen. Sierra runs on the truck display; this grants the permissions it needs. */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
            addView(TextView(context).apply {
                text = "Sierra ${versionName()}"
                textSize = 24f
            })
            addView(TextView(context).apply {
                text = "Plug the phone into the truck and open Sierra in Android Auto."
                textSize = 16f
                setPadding(0, 24, 0, 24)
            })
            addView(status)
            addView(Button(context).apply {
                text = "Grant permissions"
                setOnClickListener { requestMissing() }
            })
        }
        setContentView(layout)
        refresh()
        requestMissing()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun missing(): List<String> =
        REQUIRED.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun requestMissing() {
        val needed = missing()
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun refresh() {
        val needed = missing()
        status.text = if (needed.isEmpty()) {
            "All permissions granted."
        } else {
            "Missing: " + needed.joinToString { it.substringAfterLast('.') }
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    companion object {
        val REQUIRED = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
