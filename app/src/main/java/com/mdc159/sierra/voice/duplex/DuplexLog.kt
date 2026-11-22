package com.mdc159.sierra.voice.duplex

import android.util.Log

/**
 * Full-duplex diagnostics, read with `adb logcat -s SierraDuplex`. Session lifecycle, server
 * event types, mic levels, playback, interruption and tool timing. Never audio, API keys,
 * tokens, auth headers or signed URLs: callers log event types and counts, and anything that
 * came from a service goes through [redact] first.
 */
object DuplexLog {
    const val TAG = "SierraDuplex"

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun w(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    /** One line, bounded, with anything shaped like a credential or a query string removed. */
    fun redact(text: String?, max: Int = 200): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace(SECRET, "<redacted>")
            .replace(QUERY, "?<redacted>")
            .replace('\n', ' ')
            .take(max)
    }

    private val SECRET = Regex("(?i)\\b(?:sk|xi|pk)[-_][A-Za-z0-9_\\-*.]{6,}|(?<=bearer )[A-Za-z0-9_\\-.=]{8,}")
    private val QUERY = Regex("\\?[^\\s\"']+")
}
