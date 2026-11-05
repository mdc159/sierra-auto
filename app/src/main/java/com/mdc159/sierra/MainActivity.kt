package com.mdc159.sierra

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Phone-side screen. Sierra runs on the truck display through Android Auto. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            text = "Sierra ${BuildInfo.versionName(this@MainActivity)}\n\n" +
                "Plug the phone into the truck and open Sierra in Android Auto."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(text)
    }
}

object BuildInfo {
    fun versionName(activity: ComponentActivity): String =
        runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull() ?: "?"
}
