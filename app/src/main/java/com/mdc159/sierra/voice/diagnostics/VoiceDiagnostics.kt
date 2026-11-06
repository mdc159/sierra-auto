package com.mdc159.sierra.voice.diagnostics

import android.content.Context
import android.os.Build
import com.mdc159.sierra.BuildConfig
import com.mdc159.sierra.voice.stt.MicLevels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Bounded, local diagnostics. Callers never pass audio, transcripts, URLs or credentials. */
object VoiceDiagnostics {
    private const val PREFS = "voice_diagnostics"
    private const val LOG = "events"
    private const val LIMIT = 120
    private val lock = Any()
    @Volatile var liveRms = 0.0
        private set
    @Volatile var recording = false
        private set

    fun event(context: Context, stage: String, detail: String) = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val rows = prefs.getString(LOG, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toMutableList()
        rows += "$time ${safe(stage)}: ${safe(detail)}"
        prefs.edit().putString(LOG, rows.takeLast(LIMIT).joinToString("\n")).apply()
    }

    fun mic(context: Context, source: String, levels: MicLevels) {
        liveRms = levels.meanRms
        event(context, "Mic $source", levels.summary())
    }

    fun opened(context: Context, source: String) {
        recording = true
        liveRms = 0.0
        event(context, "Microphone", "$source opened")
    }

    fun closed(context: Context) {
        recording = false
        liveRms = 0.0
        event(context, "Microphone", "closed")
    }

    /** Only known numeric/enum fields from the endpointer log, never its free-text errors. */
    fun recorder(context: Context, line: String) {
        val fields = Regex("\\b(outcome|verdict|heardSpeech|floor|threshold|initialFloor|sendUnconfirmed)=([A-Za-z0-9.+_-]+)")
            .findAll(line).joinToString(" ") { it.value }
        if (fields.isNotBlank()) event(context, "Speech detector", fields)
    }

    fun failure(context: Context, stage: String, error: Throwable) = event(context, stage, failureCode(error))

    fun failureCode(error: Throwable): String {
        val http = Regex("(?:HTTP |error )(\\d{3})", RegexOption.IGNORE_CASE).find(error.message.orEmpty())?.groupValues?.get(1)
        return when {
            http != null -> "HTTP $http"
            error is SecurityException -> "microphone permission or background access denied"
            error is java.net.SocketTimeoutException || error.message.orEmpty().contains("timed out", true) -> "timeout"
            error is java.net.UnknownHostException -> "host lookup failed; check network and Tailscale"
            else -> error.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(60)
        }
    }

    fun report(context: Context): String {
        val events = synchronized(lock) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LOG, "").orEmpty() }
        return "Sierra ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "Android API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Selected microphone: ${MicSettings.source(context).label}\n" +
            "Phone microphone service: ${PhoneMicService.ready}\n" +
            "${MicSettings.health(context)}\n" +
            "Times UTC. Levels and status only; no recordings, transcripts, addresses or keys.\n\n" +
            events.ifBlank { "No voice attempt recorded yet. Try Talk or Conversation, then return here." }
    }

    fun clear(context: Context) = synchronized(lock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(LOG).apply()
    }

    private fun safe(text: String): String = text
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[address removed]")
        .replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "[key removed]")
        .replace(Regex("\\bsk-[A-Za-z0-9_-]+"), "[key removed]")
        .replace('\n', ' ').replace('\r', ' ').take(500)
}
