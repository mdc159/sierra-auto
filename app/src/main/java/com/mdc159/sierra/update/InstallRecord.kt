package com.mdc159.sierra.update

import android.content.Context
import android.os.Build

/**
 * How Android says this app was installed. Android Auto reads the same record before it lists a
 * Car App Library app on a real head unit (see docs/UPDATES.md for the sources behind [looksLikePlay]).
 */
data class InstallRecord(
    /** Installer of record (`getInstallingPackageName`). Changeable by privileged installers. */
    val installing: String?,
    /** The package that really performed the install (`getInitiatingPackageName`). Not changeable. */
    val initiating: String?,
    /** `PackageInstaller.PACKAGE_SOURCE_*`, Android 13+. Null when unknown. */
    val packageSource: Int?,
    /** Android 14+ update owner, or null when ownership enforcement is off. */
    val updateOwner: String?,
) {
    /**
     * The pair Android Auto 17.7 accepts according to the decompiled validator and to UnknownAuto's
     * own acceptance check: Play as installer of record, and the initiator either Play itself or
     * the system package installer (Google's or an OEM fork such as Samsung's).
     */
    val looksLikePlay: Boolean
        get() = installing == PLAY &&
            (initiating == PLAY || initiating?.contains("packageinstaller") == true)

    /** `adb install`, with or without `-i`: the shell is always the initiator. */
    val looksLikeAdb: Boolean
        get() = initiating == SHELL

    fun summary(): String = buildString {
        append("Installer of record: ").append(installing ?: "none").append('\n')
        append("Initiating package: ").append(initiating ?: "none").append('\n')
        append("Package source: ").append(sourceName(packageSource))
        if (updateOwner != null) append('\n').append("Update owner: ").append(updateOwner)
    }

    fun verdict(): String = when {
        looksLikePlay -> "Recorded like a Play install. Android Auto should list Sierra in the car (not yet verified on the truck)."
        looksLikeAdb -> "Installed over adb. The Desktop Head Unit does not check, but a real head unit is expected to hide Sierra even with Play as installer of record."
        else -> "Not a Play install. The Desktop Head Unit does not check, but a real head unit is expected to hide Sierra."
    }

    companion object {
        const val PLAY = "com.android.vending"
        const val SHELL = "com.android.shell"

        fun sourceName(code: Int?): String = when (code) {
            null -> "unknown"
            0 -> "unspecified (0)"
            1 -> "other (1)"
            2 -> "store (2)"
            3 -> "local file (3)"
            4 -> "downloaded file (4)"
            else -> "code $code"
        }

        @Suppress("DEPRECATION")
        fun read(context: Context): InstallRecord {
            val pm = context.packageManager
            val pkg = context.packageName
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return InstallRecord(pm.getInstallerPackageName(pkg), null, null, null)
            }
            val info = pm.getInstallSourceInfo(pkg)
            val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) info.packageSource else null
            val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.updateOwnerPackageName else null
            return InstallRecord(info.installingPackageName, info.initiatingPackageName, source, owner)
        }
    }
}
