package com.mdc159.sierra.update

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import androidx.core.content.FileProvider
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException

/** Optional install path through Shizuku. The app works without Shizuku installed. */
class ShizukuInstaller(private val context: Context) {

    enum class Status(val label: String) {
        NOT_INSTALLED("Shizuku is not installed."),
        NOT_RUNNING("Shizuku is installed but not running. Turn on Wireless debugging and tap Start in Shizuku."),
        TOO_OLD("Shizuku is too old. Update it to version 11 or newer."),
        NO_PERMISSION("Shizuku is running. Sierra needs permission to use it."),
        READY("Shizuku is running and Sierra may use it."),
    }

    fun status(): Status {
        if (!isInstalled()) return Status.NOT_INSTALLED
        if (!Shizuku.pingBinder()) return Status.NOT_RUNNING
        if (Shizuku.isPreV11()) return Status.TOO_OLD
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) Status.READY else Status.NO_PERMISSION
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
    }.isSuccess

    fun requestPermission(requestCode: Int) {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(requestCode)
    }

    /**
     * Hands [apk] to the system package installer through the shell. The installer then shows its
     * own confirmation on the phone. [onResult] is called on the main thread with a short message,
     * or an exception when the hand-off itself failed.
     */
    fun install(apk: File, onResult: (Result<String>) -> Unit) {
        val intent = try {
            buildInstallerIntent(apk)
        } catch (e: Exception) {
            onResult(Result.failure(e))
            return
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellInstallService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(SERVICE_VERSION)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val result = runCatching {
                    if (binder == null || !binder.pingBinder()) throw IOException("Shizuku service did not start.")
                    callStart(binder, intent)
                }
                runCatching { Shizuku.unbindUserService(args, this, true) }
                onResult(result)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            onResult(Result.failure(e))
        }
    }

    private fun callStart(binder: IBinder, intent: Intent): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShellInstallService.DESCRIPTOR)
            intent.writeToParcel(data, 0)
            binder.transact(ShellInstallService.CODE_START, data, reply, 0)
            reply.readException()
            val code = reply.readInt()
            val message = reply.readString() ?: ""
            if (code < 0) throw IOException("Shell could not start the installer: $message")
            return "Installer opened through the shell ($message). Confirm on the phone, then come back here to read the installer record."
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Revokes the read grant [buildInstallerIntent] gave the system installer. The grant is made
     * outside any activity result flow, so it would otherwise last until revoked.
     */
    fun revokeGrant(apk: File) {
        if (!apk.exists()) return
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildInstallerIntent(apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        @Suppress("DEPRECATION")
        val probe = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME)
        val resolved = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?: throw IOException("No system package installer found for APK files.")
        val component = ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name)
        // The shell cannot grant our content URI, so grant the installer read access from here.
        context.grantUriPermission(component.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setComponent(component)
            .setDataAndType(uri, APK_MIME)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, InstallRecord.PLAY)
            .putExtra(Intent.EXTRA_RETURN_RESULT, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val SERVICE_VERSION = 1
    }
}
