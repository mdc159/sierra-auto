package com.mdc159.sierra.studio54

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Phone-side Studio 54 sign-in. Shows which secret names arrived and when, never a value.
 *
 * Two ways in: type the values by hand, or receive them by intent
 * ([ACTION_IMPORT], pushed from the PC over USB). An import is never saved silently:
 * a confirmation screen names the vault host first.
 */
class Studio54Activity : ComponentActivity() {

    private val source: VaultSecretSource get() = Studio54.source(this)
    private var pad = 0
    private var statusView: TextView? = null
    private var resultView: TextView? = null
    private var refreshButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (16 * resources.displayMetrics.density).toInt()
        Studio54.start(this)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun handle(intent: Intent?) {
        if (intent == null || intent.action != ACTION_IMPORT) {
            showMain()
            return
        }
        val pending = Studio54Credentials.fromExtras { intent.getStringExtra(it) }
        val missing = Studio54Credentials.EXTRAS
            .filter { it != Studio54Credentials.EXTRA_KEYS_PROJECT_ID && intent.getStringExtra(it).isNullOrBlank() }
        // Drop the values from the activity record as soon as they are in memory.
        intent.replaceExtras(Bundle())
        if (pending == null) {
            showMain("Import ignored: missing or invalid " + missing.ifEmpty { listOf("INFISICAL_API_URL") }.joinToString())
        } else {
            showConfirm(pending)
        }
    }

    // ---- main screen -------------------------------------------------------------------

    private fun showMain(message: String? = null) {
        val form = column()
        form.addView(text("Studio 54", 24f))
        form.addView(text("Sign in once; the app then pulls its keys from the vault. Values are never shown here."))

        form.addView(heading("Status"))
        statusView = TextView(this).also { form.addView(it) }
        resultView = TextView(this).also { form.addView(it) }
        resultView?.text = message.orEmpty()

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshButton = Button(this).apply {
            text = "Refresh now"
            setOnClickListener { refresh() }
        }
        buttons.addView(refreshButton)
        buttons.addView(Button(this).apply {
            text = "Sign out"
            setOnClickListener { confirmSignOut() }
        })
        form.addView(buttons)

        form.addView(heading("Sign in by hand"))
        form.addView(text("Or push the sign-in from the PC over USB (see docs/STUDIO54.md); no value is typed or shown then."))
        val apiUrl = field(form, "Vault URL (INFISICAL_API_URL)", "http://vault.tailnet.ts.net:8080", uri = true)
        val clientId = field(form, "Client ID (INFISICAL_CLIENT_ID)", "", secret = true)
        val clientSecret = field(form, "Client secret (INFISICAL_CLIENT_SECRET)", "", secret = true)
        val projectId = field(form, "Agent project ID (INFISICAL_PROJECT_ID)", "")
        val keysProjectId = field(form, "Shared keys project ID (S54_KEYS_PROJECT_ID, optional)", "")
        source.status().let { s ->
            if (s.signedIn) {
                apiUrl.hint = "saved: ${s.vaultHost}"
                projectId.hint = "saved: ${s.projectId}"
                keysProjectId.hint = s.keysProjectId?.let { "saved: $it" } ?: "none"
                clientId.hint = "saved"
                clientSecret.hint = "saved"
            }
        }
        form.addView(Button(this).apply {
            text = "Sign in"
            setOnClickListener {
                val creds = Studio54Credentials(
                    apiUrl = apiUrl.text.toString(),
                    clientId = clientId.text.toString().trim(),
                    clientSecret = clientSecret.text.toString().trim(),
                    projectId = projectId.text.toString().trim(),
                    keysProjectId = keysProjectId.text.toString(),
                )
                if (!creds.isComplete) {
                    resultView?.text = "Vault URL, client ID, client secret and agent project ID are all required."
                    return@setOnClickListener
                }
                clientId.text.clear()
                clientSecret.text.clear()
                save(creds)
            }
        })

        setContentView(ScrollView(this).apply { addView(form) })
        renderStatus()
    }

    private fun renderStatus() {
        val view = statusView ?: return
        val s = source.status()
        val lines = mutableListOf<String>()
        if (!s.signedIn) {
            lines += "Not signed in."
        } else {
            lines += "Vault: ${s.vaultHost}"
            lines += "Agent project: ${s.projectId}"
            lines += "Shared keys project: ${s.keysProjectId ?: "none"}"
            lines += when (val t = s.lastFetched) {
                null -> "Secrets: none fetched yet."
                else -> "Fetched ${formatTime(t)}" + if (s.servingFromCache) " (serving cached copy)" else ""
            }
            if (s.names.isNotEmpty()) {
                lines += "Names (${s.names.size}): " + s.names.joinToString()
            }
        }
        s.lastError?.let { lines += "Last error: $it" }
        view.text = lines.joinToString("\n")
    }

    private fun refresh() {
        if (!source.isSignedIn) {
            resultView?.text = "Sign in first."
            return
        }
        refreshButton?.isEnabled = false
        resultView?.text = "Refreshing…"
        lifecycleScope.launch {
            val result = Studio54.refreshNow(this@Studio54Activity)
            resultView?.text = describe(result)
            refreshButton?.isEnabled = true
            renderStatus()
        }
    }

    private fun save(creds: Studio54Credentials) {
        source.signIn(creds)
        showMain("Signed in to ${creds.vaultHost}. Fetching…")
        refresh()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of Studio 54?")
            .setMessage("The sign-in and the cached secrets are wiped from this phone. To block the phone for good, also remove its agent on the server.")
            .setPositiveButton("Sign out") { _, _ ->
                source.signOut()
                Toast.makeText(this, "Signed out.", Toast.LENGTH_SHORT).show()
                showMain("Signed out. Sign-in and cache wiped.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- import confirmation ------------------------------------------------------------

    private fun showConfirm(pending: Studio54Credentials) {
        val form = column()
        form.addView(text("Import Studio 54 sign-in?", 24f))
        form.addView(text("Another process (normally the PC over USB) sent a sign-in to this phone."))
        form.addView(heading("Vault host"))
        form.addView(text(pending.vaultHost, 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        form.addView(text("Agent project: ${pending.projectId}"))
        form.addView(text("Shared keys project: ${pending.keysProjectId ?: "none"}"))
        val current = source.status()
        if (current.signedIn) {
            form.addView(text("This replaces the current sign-in to ${current.vaultHost} (project ${current.projectId})."))
        }
        form.addView(text("Save only if you started this from your own PC just now. Nothing is stored until you tap Save."))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Save and sign in"
            setOnClickListener { save(pending) }
        })
        buttons.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { showMain("Import cancelled. Nothing was saved.") }
        })
        form.addView(buttons)
        setContentView(ScrollView(this).apply { addView(form) })
    }

    // ---- helpers ---------------------------------------------------------------------

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad * 2, pad, pad)
    }

    private fun text(value: String, size: Float? = null) = TextView(this).apply {
        text = value
        size?.let { textSize = it }
    }

    private fun heading(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        setPadding(0, pad, 0, pad / 2)
    }

    private fun field(form: LinearLayout, label: String, hint: String, secret: Boolean = false, uri: Boolean = false): EditText {
        form.addView(TextView(this).apply { text = label })
        return EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            form.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun formatTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))

    companion object {
        /** Explicit action the PC uses: `adb shell am start -a com.mdc159.sierra.action.IMPORT_STUDIO54 …`. */
        const val ACTION_IMPORT = "com.mdc159.sierra.action.IMPORT_STUDIO54"

        fun describe(result: RefreshResult): String = when (result) {
            is RefreshResult.Fetched -> "Fetched ${result.names.size} secret name(s)."
            is RefreshResult.Failed -> buildString {
                append(result.message)
                if (result.cached) append(" Serving the cached copy.")
                else if (result.unreachable) append(" No cache to fall back on.")
            }
            RefreshResult.NotSignedIn -> "Not signed in."
        }
    }
}
