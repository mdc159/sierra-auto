package com.mdc159.sierra.voice

import android.util.Log

/**
 * Car microphone diagnostics, read with `adb logcat -s SierraMic`. Levels, outcomes and end
 * reasons only: never audio, transcripts or keys.
 */
object MicLog {
    const val TAG = "SierraMic"

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
