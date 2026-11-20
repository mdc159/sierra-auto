package com.mdc159.sierra.voice.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mdc159.sierra.R
import com.mdc159.sierra.voice.duplex.MicSource
import com.mdc159.sierra.voice.duplex.DuplexException
import java.util.concurrent.CopyOnWriteArraySet

/** Started only by a tap in the visible phone Activity, never from the car or on boot. */
class PhoneMicService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkNotification = object : Runnable {
        override fun run() {
            if (!MicSettings.notificationsAllowed(this@PhoneMicService)) {
                VoiceDiagnostics.event(this@PhoneMicService, "Phone microphone", "notification permission disabled; stopping access")
                stopSelf()
            } else handler.postDelayed(this, 5_000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        try {
            if (!MicSettings.notificationsAllowed(this)) {
                synchronized(active) { ready = false }
                VoiceDiagnostics.event(this, "Enable phone microphone", "notifications blocked; Stop action unavailable")
                stopSelf()
                return START_NOT_STICKY
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Phone microphone", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MicDiagnosticsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, PhoneMicService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic).setContentTitle("Sierra phone microphone enabled")
                .setContentText("Ready for Talk and Conversation. Tap for diagnostics; Stop disables access.")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(2901, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(2901, notification)
            synchronized(active) { ready = true }
            handler.removeCallbacks(checkNotification)
            handler.postDelayed(checkNotification, 5_000)
            VoiceDiagnostics.event(this, "Phone microphone", "background access enabled by phone button")
        } catch (e: Exception) {
            synchronized(active) { ready = false }
            VoiceDiagnostics.failure(this, "Enable phone microphone", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkNotification)
        // Register and disable are atomic, but stop outside the registry lock: mic.stop unregisters.
        val microphones = synchronized(active) { ready = false; active.toList() }
        microphones.forEach { runCatching { it.stop() }.onFailure { VoiceDiagnostics.failure(this, "Phone microphone stop", it) } }
        VoiceDiagnostics.event(this, "Phone microphone", "background access disabled")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "phone_microphone"
        private const val STOP = "stop"
        @Volatile var ready = false
            private set
        private val active = CopyOnWriteArraySet<MicSource>()
        internal fun register(mic: MicSource, requireService: Boolean) = synchronized(active) {
            if (requireService && !ready) throw DuplexException("Enable Phone microphone on the phone first.")
            active += mic
        }
        internal fun unregister(mic: MicSource) { synchronized(active) { active -= mic } }
    }
}
