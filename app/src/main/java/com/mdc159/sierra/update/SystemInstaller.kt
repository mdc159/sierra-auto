package com.mdc159.sierra.update

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Opens the system package installer from Sierra itself, asking it to record Play as installer of
 * record. No Shizuku and no PC are involved.
 *
 * Why this works at all: the installer keeps `EXTRA_INSTALLER_PACKAGE_NAME` or drops it depending
 * on who launched it. In the Google package installer version 17 (the one on the Galaxy A54 with
 * Android 16, `v2.model.InstallRepository`), the extra is dropped when the launcher HOLDS
 * `INSTALL_PACKAGES` and kept when it does not. That is the reverse of the older installer, which
 * keeps it only for a launcher that holds the permission (the shell). So on that installer a plain
 * app such as Sierra should get installer of record `com.android.vending` with the system
 * installer as initiating package, and the shell (adb or Shizuku) does not.
 *
 * The shell half is measured on the A54. The app half is read from the installer's code and from
 * UnknownAuto's notes; it could not be measured there because that phone's work profile sets
 * `no_install_unknown_sources_globally`, which makes the installer refuse every ordinary app
 * ([blockedByPolicy]). See docs/TRUCK-INSTALL.md.
 */
object SystemInstaller {

    const val APK_MIME = "application/vnd.android.package-archive"

    const val POLICY_MESSAGE = "A device policy on this phone (usually a work profile) forbids installs from " +
        "unknown sources. Sierra cannot install an update itself here; only adb or Google Play can."

    /**
     * True when a device policy forbids app installs from unknown sources for this user. The system
     * installer then refuses any launcher that does not hold INSTALL_PACKAGES, and "Install unknown
     * apps" shows "Disabled by admin" in Settings.
     */
    fun blockedByPolicy(context: Context): Boolean {
        val users = context.getSystemService(UserManager::class.java) ?: return false
        return users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    }

    /** The install request for [apk], addressed to the phone's system package installer. */
    fun intent(context: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        @Suppress("DEPRECATION")
        val probe = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME)
        val resolved = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?: throw IOException("No system package installer found for APK files.")
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setComponent(ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name))
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, InstallRecord.PLAY)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }
}
