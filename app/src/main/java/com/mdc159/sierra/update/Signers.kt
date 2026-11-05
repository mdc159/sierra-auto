package com.mdc159.sierra.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Pure helpers for comparing signing certificates. Kept free of Android types so JVM tests can cover them. */
object Fingerprints {
    /** Upper-case, colon-separated SHA-256 of [bytes], the same shape `apksigner --print-certs` prints. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }

    /** True only when both sides are non-empty and sign with exactly the same certificates. */
    fun sameSigner(installed: Set<String>, candidate: Set<String>): Boolean =
        installed.isNotEmpty() && installed == candidate
}

/** What a downloaded APK says about itself, before anything is installed. */
data class ApkFacts(val packageName: String?, val versionCode: Long, val signers: Set<String>)

/** Reads signing certificates of the installed app and of an APK file through PackageManager. */
object ApkSigners {

    // Both flags: some OS builds hand back a PackageInfo whose signingInfo is null for an
    // archive, while the older signatures array is still filled in.
    @Suppress("DEPRECATION")
    private const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    fun installed(context: Context): Set<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, FLAGS)
        return signersOf(info)
    }

    @Suppress("DEPRECATION")
    fun ofApk(context: Context, apk: File): ApkFacts {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, FLAGS)
            ?: throw IOException("Not a readable APK.")
        return ApkFacts(info.packageName, info.longVersionCode, signersOf(info))
    }

    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo): Set<String> {
        val current = info.signingInfo?.apkContentsSigners
        val signatures = if (!current.isNullOrEmpty()) current else info.signatures
        return signatures.orEmpty().map { Fingerprints.sha256(it.toByteArray()) }.toSet()
    }
}
