package com.mdc159.sierra.update

import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Parcel
import kotlin.system.exitProcess

/**
 * Runs in a Shizuku user service, as the shell user (uid 2000) when Shizuku was started over
 * wireless debugging. Nothing in this class runs inside the app process.
 *
 * It asks the activity manager to start the system package installer with `com.android.shell`
 * as the calling package. The shell holds INSTALL_PACKAGES, so the installer keeps the
 * EXTRA_INSTALLER_PACKAGE_NAME extra and records Play as installer of record, while the initiating
 * package becomes the package installer itself. That is the pair Android Auto looks for. The
 * approach is the one UnknownAuto uses; see docs/UPDATES.md for sources and caveats.
 *
 * Hidden framework APIs are reached by reflection. The user service is started by `app_process`,
 * which does not apply the hidden API blocklist the way an app process does. If that ever fails,
 * the exception text is returned to the app instead of crashing the service.
 */
class ShellInstallService : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            CODE_START -> {
                data.enforceInterface(DESCRIPTOR)
                val intent = Intent.CREATOR.createFromParcel(data)
                val (result, message) = runCatching { startAsShell(intent) }
                    .getOrElse { -1 to it.toString() }
                reply?.writeNoException()
                reply?.writeInt(result)
                reply?.writeString(message)
                true
            }
            CODE_DESTROY -> exitProcess(0)
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun startAsShell(intent: Intent): Pair<Int, String> {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            ?: throw IllegalStateException("ActivityTaskManager.getService() returned null")
        val wantedParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 11 else 10
        val start = atm.javaClass.methods.firstOrNull { it.name == "startActivity" && it.parameterTypes.size == wantedParams }
            ?: throw NoSuchMethodException("IActivityTaskManager.startActivity with $wantedParams parameters")
        val args: Array<Any?> = if (wantedParams == 11) {
            // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
            //  requestCode, startFlags, profilerInfo, options)
            arrayOf(null, InstallRecord.SHELL, null, intent, intent.type, null, null, 0, 0, null, null)
        } else {
            arrayOf(null, InstallRecord.SHELL, intent, intent.type, null, null, 0, 0, null, null)
        }
        val result = start.invoke(atm, *args) as Int
        return result to "startActivity returned $result"
    }

    companion object {
        const val DESCRIPTOR = "com.mdc159.sierra.update.IShellInstall"
        const val CODE_START = 1
        /** Shizuku's reserved transaction code for stopping a user service. */
        const val CODE_DESTROY = 16777115
    }
}
