package com.mdc159.sierra.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import java.io.File

/**
 * Installs an APK through a PackageInstaller session owned by Sierra itself. This is the plain
 * in-app updater. Android records Sierra as both installer and initiator of the result, so it
 * replaces any Play install record (see docs/UPDATES.md, option d).
 */
object SessionInstaller {
    const val ACTION_STATUS = "com.mdc159.sierra.update.INSTALL_STATUS"
    private const val APK_ENTRY = "base.apk"

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Honoured only once Sierra is its own installer of record; otherwise the system
                // falls back to asking the user, which UpdateStatusReceiver handles.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_ENTRY, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(context, UpdateStatusReceiver::class.java).setAction(ACTION_STATUS)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                status,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
    }

    /** Human-readable text for a PackageInstaller status broadcast. */
    fun describe(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "Installed."
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "Waiting for you to confirm on the phone."
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked: ${message ?: "by the system"}"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflict: ${message ?: "signature or package mismatch"}"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Incompatible: ${message ?: ""}"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid APK: ${message ?: ""}"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage."
        else -> "Install failed ($status): ${message ?: ""}"
    }
}

/** Receives session status. Listeners are set by UpdateActivity while it is visible. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
        }
        listener?.invoke(status, message)
    }

    companion object {
        @Volatile
        var listener: ((status: Int, message: String?) -> Unit)? = null
    }
}
