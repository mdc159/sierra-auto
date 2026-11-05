package com.mdc159.sierra

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.agent.AgentClient
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.studio54.Studio54Activity
import com.mdc159.sierra.voice.Pcm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.update.UpdateActivity

/**
 * Phone-side settings. Sierra runs on the truck display; this is where the agent
 * endpoint is configured (never while driving) and permissions are granted.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var model: EditText
    private lateinit var sttUrl: EditText
    private lateinit var sttKey: EditText
    private lateinit var sttModel: EditText
    private lateinit var testButton: Button
    private lateinit var testResult: TextView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissions() }

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
        fun field(label: String, hint: String, secret: Boolean = false): EditText {
            form.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                this.hint = hint
                isSingleLine = true
                inputType = if (secret) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                form.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        form.addView(TextView(this).apply {
            text = "Sierra ${versionName()}"
            textSize = 24f
        })
        form.addView(TextView(this).apply {
            text = "Set up your agent here, then open Sierra in Android Auto."
        })

        heading("Agent (OpenAI-compatible)")
        baseUrl = field("Base URL", "https://example.com/v1")
        apiKey = field("API key", "sk-…", secret = true)
        model = field("Model", "gpt-4o-mini")

        heading("Transcription (optional)")
        form.addView(TextView(this).apply {
            text = "If set, Sierra records with the car microphone and posts audio to " +
                "/v1/audio/transcriptions. If blank, the phone's speech recognizer is used."
        })
        sttUrl = field("Transcription URL", "https://example.com/v1")
        sttKey = field("Transcription key (blank = agent key)", "", secret = true)
        sttModel = field("Transcription model", AgentConfig.DEFAULT_STT_MODEL)

        form.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        })
        testButton = Button(this).apply {
            text = "Test connection"
            setOnClickListener { testConnection() }
        }
        form.addView(testButton)
        testResult = TextView(this)
        form.addView(testResult)

        form.addView(Button(this).apply {
            text = "Studio 54 sign-in"
            setOnClickListener { startActivity(Intent(this@MainActivity, Studio54Activity::class.java)) }
        })

        heading("Truck data (OBD-II)")
        form.addView(Button(this).apply {
            text = "Truck data settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, ObdSettingsActivity::class.java)) }
        })

        heading("Permissions")
        status = TextView(this)
        form.addView(status)
        form.addView(Button(this).apply {
            text = "Grant permissions"
            setOnClickListener { requestMissing() }
        })

        heading("Idea loop")
        form.addView(Button(this).apply {
            text = "Idea settings (GitHub token)"
            setOnClickListener { startActivity(Intent(this@MainActivity, IdeaSettingsActivity::class.java)) }
        })

        heading("Updates")
        form.addView(Button(this).apply {
            text = "Updates and install record"
            setOnClickListener { startActivity(Intent(this@MainActivity, UpdateActivity::class.java)) }
        })

        setContentView(ScrollView(this).apply { addView(form) })
        loadConfig()
        refreshPermissions()
        requestMissing()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun loadConfig() {
        val c = ConfigStore.load(this)
        baseUrl.setText(c.baseUrl)
        apiKey.setText(c.apiKey)
        model.setText(c.model)
        sttUrl.setText(c.sttUrl)
        sttKey.setText(c.sttKey)
        sttModel.setText(c.sttModel)
    }

    private fun currentConfig() = AgentConfig(
        baseUrl = baseUrl.text.toString().trim(),
        apiKey = apiKey.text.toString().trim(),
        model = model.text.toString().trim(),
        sttUrl = sttUrl.text.toString().trim(),
        sttKey = sttKey.text.toString().trim(),
        sttModel = sttModel.text.toString().trim().ifBlank { AgentConfig.DEFAULT_STT_MODEL },
    )

    private fun save() {
        val config = currentConfig()
        ConfigStore.save(this, config)
        val note = if (config.isConfigured) "Saved." else "Saved. Base URL and model are required."
        Toast.makeText(this, note, Toast.LENGTH_SHORT).show()
    }

    /** Saves, then sends a tiny chat request (and a silent clip, if transcription is set). */
    private fun testConnection() {
        val config = currentConfig()
        ConfigStore.save(this, config)
        if (!config.isConfigured) {
            testResult.text = "Enter a base URL and model first."
            return
        }
        testButton.isEnabled = false
        testResult.text = "Testing…"
        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            val client = AgentClient(config)
            lines += try {
                val reply = withContext(Dispatchers.IO) { client.testConnection() }
                "Agent OK: \"$reply\""
            } catch (e: Exception) {
                "Agent failed: ${e.message ?: Http.describe(e)}"
            }
            if (config.hasTranscription) {
                lines += try {
                    withContext(Dispatchers.IO) { client.transcribe(Pcm.toWav(ByteArray(Pcm.SAMPLE_RATE))) }
                    "Transcription OK."
                } catch (e: Exception) {
                    "Transcription failed: ${e.message ?: Http.describe(e)}"
                }
            }
            testResult.text = lines.joinToString(separator = "\n")
            testButton.isEnabled = true
        }
    }

    private fun missing(): List<String> =
        REQUIRED.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun requestMissing() {
        val needed = missing().toMutableSet()
        // Android 12+ ignores a fine-only request after an approximate grant; ask for both.
        if (needed.any { it.contains("LOCATION") }) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun refreshPermissions() {
        if (!::status.isInitialized) return
        val needed = missing()
        status.text = if (needed.isEmpty()) {
            "Location and microphone granted."
        } else {
            "Missing: " + needed.joinToString { it.substringAfterLast('.') }
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    companion object {
        val REQUIRED = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
