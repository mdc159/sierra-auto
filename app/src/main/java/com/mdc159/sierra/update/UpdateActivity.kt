package com.mdc159.sierra.update

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.BuildConfig
import com.mdc159.sierra.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Phone-side update screen. Shows how the current build was installed (the record Android Auto
 * checks), fetches the latest GitHub Release, verifies its signer against the installed app and
 * installs it either through Sierra's own PackageInstaller session or, optionally, through Shizuku.
 */
class UpdateActivity : ComponentActivity() {

    private lateinit var installed: TextView
    private lateinit var record: TextView
    private lateinit var verdict: TextView
    private lateinit var latest: TextView
    private lateinit var unknownSources: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var log: TextView
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var installButton: Button
    private lateinit var shizukuInstallButton: Button
    private lateinit var shizukuAllowButton: Button

    private var release: ReleaseInfo? = null
    private var apk: File? = null
    private val shizuku by lazy { ShizukuInstaller(this) }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == SHIZUKU_REQUEST) refreshShizuku()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun heading(text: String) = form.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })
        fun line() = TextView(this).also { form.addView(it) }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            form.addView(this)
        }

        form.addView(TextView(this).apply {
            text = "Updates"
            textSize = 24f
        })
        installed = line()

        heading("How this build was installed")
        form.addView(TextView(this).apply {
            text = "Android Auto only lists Car App Library apps whose install record points at Google Play. " +
                "The Desktop Head Unit skips that check, so this screen is the only way to see it."
        })
        record = line()
        verdict = line()

        val sideloadFrom = form.childCount
        heading("Latest release on GitHub")
        latest = line()
        checkButton = button("Check for updates") { check() }
        downloadButton = button("Download and verify signer") { download() }
        downloadButton.isEnabled = false

        heading("Install")
        unknownSources = line()
        button("Allow Sierra to install apps") { openUnknownSourcesSettings() }
        installButton = button("Install (Sierra becomes the installer)") { installWithSession() }
        installButton.isEnabled = false
        form.addView(TextView(this).apply {
            text = "This path replaces the Play record. Use it on the Desktop Head Unit or after moving to Play."
        })

        heading("Install through Shizuku (optional, experimental)")
        form.addView(TextView(this).apply {
            text = "With Shizuku running (Wireless debugging, no PC), the shell opens the system installer " +
                "so Play is recorded as installer. Same method as UnknownAuto. Not yet verified on this phone."
        })
        shizukuStatus = line()
        shizukuAllowButton = button("Allow Sierra in Shizuku") { shizuku.requestPermission(SHIZUKU_REQUEST) }
        shizukuInstallButton = button("Install through Shizuku") { installWithShizuku() }
        shizukuInstallButton.isEnabled = false
        if (BuildConfig.PLAY_BUILD) {
            // Play policy: a Play-distributed app updates only through Play. A GitHub or Shizuku
            // install would also replace the Play install record the truck needs.
            for (i in sideloadFrom until form.childCount) form.getChildAt(i).visibility = View.GONE
            heading("Updates come from Google Play")
            form.addView(TextView(this).apply {
                text = "This copy was built for Play internal testing. New versions arrive through the Play Store."
            })
        }

        heading("Log")
        log = line()

        setContentView(ScrollView(this).apply { addView(form) })
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onResume() {
        super.onResume()
        // A Shizuku install grants the system installer read access to the APK; take it back.
        shizuku.revokeGrant(File(File(cacheDir, "updates"), ReleaseInfo.APK_NAME))
        refreshInstalled()
        refreshUnknownSources()
        refreshShizuku()
        UpdateStatusReceiver.listener = { status, message -> say(SessionInstaller.describe(status, message)) }
    }

    override fun onPause() {
        UpdateStatusReceiver.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun installedVersionCode(): Long =
        runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)

    private fun refreshInstalled() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        installed.text = "Installed: ${info?.versionName ?: "?"} (versionCode ${info?.longVersionCode ?: "?"})"
        val r = runCatching { InstallRecord.read(this) }.getOrNull()
        record.text = r?.summary() ?: "Could not read the install record."
        verdict.text = r?.verdict() ?: ""
    }

    private fun refreshUnknownSources() {
        val allowed = packageManager.canRequestPackageInstalls()
        unknownSources.text = if (allowed) {
            "Sierra may install apps (\"Install unknown apps\" is on)."
        } else {
            "\"Install unknown apps\" is off for Sierra. Needed for the normal install path."
        }
    }

    private fun refreshShizuku() {
        val status = runCatching { shizuku.status() }.getOrElse { ShizukuInstaller.Status.NOT_RUNNING }
        shizukuStatus.text = status.label
        shizukuAllowButton.isEnabled = status == ShizukuInstaller.Status.NO_PERMISSION
        shizukuInstallButton.isEnabled = status == ShizukuInstaller.Status.READY && apk != null
    }

    private fun openUnknownSourcesSettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun check() {
        checkButton.isEnabled = false
        latest.text = "Checking…"
        lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { GitHubReleases().latest() }
                release = found
                val mine = installedVersionCode()
                val relation = when {
                    found.isNewerThan(mine) -> "newer than this build"
                    found.versionCode.toLong() == mine -> "the same build as this one"
                    else -> "older than this build (a Play build has a higher versionCode)"
                }
                latest.text = "${found.tag} (versionCode ${found.versionCode}), $relation.\n" +
                    found.notes.trim().take(300)
                downloadButton.isEnabled = true
            } catch (e: Exception) {
                latest.text = "Check failed: ${e.message ?: Http.describe(e)}"
            }
            checkButton.isEnabled = true
        }
    }

    private fun download() {
        val found = release ?: return
        downloadButton.isEnabled = false
        installButton.isEnabled = false
        shizukuInstallButton.isEnabled = false
        apk = null
        say("Downloading ${found.tag}…")
        lifecycleScope.launch {
            try {
                val target = File(File(cacheDir, "updates"), ReleaseInfo.APK_NAME)
                val file = withContext(Dispatchers.IO) {
                    GitHubReleases().download(found.apkUrl, target) { p ->
                        val percent = (p * 100).toInt()
                        if (percent % 10 == 0) runOnUiThread { say("Downloading… $percent%") }
                    }
                }
                val facts = withContext(Dispatchers.IO) { ApkSigners.ofApk(this@UpdateActivity, file) }
                val mine = withContext(Dispatchers.IO) { ApkSigners.installed(this@UpdateActivity) }
                when {
                    facts.packageName != packageName ->
                        say("Refusing: the APK is ${facts.packageName}, not $packageName.")
                    facts.signers.isEmpty() ->
                        say("Refusing: could not read the downloaded APK's signer (PackageManager returned no certificates).")
                    mine.isEmpty() ->
                        say("Refusing: could not read the installed app's signer.")
                    !Fingerprints.sameSigner(mine, facts.signers) ->
                        say("Refusing: the APK is signed with a different key.\nInstalled: ${mine.joinToString()}\nDownloaded: ${facts.signers.joinToString()}")
                    else -> {
                        apk = file
                        say("Downloaded ${found.tag} (versionCode ${facts.versionCode}). Signer matches the installed app.")
                        installButton.isEnabled = true
                        refreshShizuku()
                    }
                }
            } catch (e: Exception) {
                say("Download failed: ${e.message ?: Http.describe(e)}")
            }
            downloadButton.isEnabled = true
        }
    }

    private fun installWithSession() {
        val file = apk ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            say("Turn on \"Install unknown apps\" for Sierra first.")
            openUnknownSourcesSettings()
            return
        }
        say("Starting install session…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { SessionInstaller.install(this@UpdateActivity, file) }
                say("Session committed. Confirm on the phone if asked.")
            } catch (e: Exception) {
                say("Install failed: ${e.message ?: e.toString()}")
            }
        }
    }

    private fun installWithShizuku() {
        val file = apk ?: return
        say("Asking the shell to open the system installer…")
        shizukuInstallButton.isEnabled = false
        shizuku.install(file) { result ->
            result.onSuccess { say(it) }.onFailure { say("Shizuku path failed: ${it.message ?: it.toString()}") }
            refreshShizuku()
        }
    }

    private fun say(text: String) {
        log.text = text
    }

    companion object {
        private const val SHIZUKU_REQUEST = 4101
    }
}
