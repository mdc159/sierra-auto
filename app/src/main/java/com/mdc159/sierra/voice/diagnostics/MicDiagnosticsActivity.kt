package com.mdc159.sierra.voice.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.BuildConfig
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.studio54.Studio54
import com.mdc159.sierra.voice.convo.VoiceSettingsStore
import com.mdc159.sierra.voice.convo.VoiceStack
import com.mdc159.sierra.voice.duplex.MicSource
import com.mdc159.sierra.voice.stt.MicLevels
import com.mdc159.sierra.voice.tts.AndroidAudioFocus
import com.mdc159.sierra.voice.tts.AudioFocusGate
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.log10

/** Cable-free status, microphone selection and a shareable diagnostic report. */
class MicDiagnosticsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var log: TextView
    private lateinit var meter: ProgressBar
    private lateinit var source: RadioGroup
    private var refresh: Job? = null
    private var test: Job? = null
    private var testMic: MicSource? = null
    private var pendingEnable = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingEnable) {
            if (MicSettings.permitted(this) && MicSettings.notificationsAllowed(this)) enablePhone()
            else result.text = "Allow microphone and notifications to enable phone access with a visible Stop action."
        }
        pendingEnable = false
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun label(text: String, size: Float = 16f) = TextView(this).also { it.text = text; it.textSize = size; form.addView(it) }
        fun button(text: String, action: () -> Unit) = Button(this).also { it.text = text; it.setOnClickListener { action() }; form.addView(it) }
        label("Microphone & diagnostics", 24f)
        label("Sierra ${BuildConfig.VERSION_NAME}. Use this screen while parked. No computer or USB debugging is needed.")
        source = RadioGroup(this).apply {
            MicrophoneSource.entries.forEachIndexed { i, value -> addView(RadioButton(this@MicDiagnosticsActivity).apply { id = 100 + i; text = value.label }) }
            check(100 + MicSettings.source(this@MicDiagnosticsActivity).ordinal)
            setOnCheckedChangeListener { _, id ->
                val selected = MicrophoneSource.entries[id - 100]
                MicSettings.source(this@MicDiagnosticsActivity, selected)
                com.mdc159.sierra.car.TalkVoice.noiseFloor = Double.NaN
                VoiceDiagnostics.event(this@MicDiagnosticsActivity, "Microphone selection", selected.label)
                if (selected != MicrophoneSource.PHONE) stopService(Intent(this@MicDiagnosticsActivity, PhoneMicService::class.java))
                refreshStatus()
            }
        }
        form.addView(source)
        label("Selection applies to both Talk and Conversation on the truck. Stop the current conversation before switching. Automatic preserves the previous car-mic / system-recognizer choice.")
        button("Enable phone microphone for Android Auto") {
            pendingEnable = true
            val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(needed.toTypedArray())
        }
        button("Disable phone microphone") { stopService(Intent(this, PhoneMicService::class.java)); refreshStatus() }
        label("Enable from this screen before returning to Android Auto. A notification keeps phone-mic access available; Stop in the notification disables it. This does not enable a wake word.")
        form.addView(CheckBox(this).apply {
            text = "Talk diagnostic mode: record until Done (maximum 20 seconds)"
            isChecked = MicSettings.manual(this@MicDiagnosticsActivity)
            setOnCheckedChangeListener { _, value -> MicSettings.manual(this@MicDiagnosticsActivity, value) }
        })
        label("Diagnostic mode bypasses Talk's local speech detector. Digital silence is still reported. Conversation streams audio and does not use this detector.")
        status = label("", 16f)
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; form.addView(this) }
        label("Live input level (logarithmic). A moving bar proves audio arrives, not that speech is understood.")
        button("Test phone mic only — 5 seconds") { testPhone(false) }
        button("Test phone mic + transcription — 5 seconds") { testPhone(true) }
        button("Stop test") { stopTest() }
        result = label("")
        label("To test the truck's microphone, open Sierra → Mic test on the truck. Return here to read or share the result. The car test sends nothing to a server.")
        button("Open microphone permission settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
        button("Copy diagnostics") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sierra diagnostics", VoiceDiagnostics.report(this)))
            Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
        }
        button("Share diagnostics") { share() }
        button("Clear diagnostics") { VoiceDiagnostics.clear(this); refreshStatus() }
        label("Diagnostics stay on this phone until you share them. Reports contain levels, permissions and stage/status codes; never audio, transcripts, API keys or endpoint addresses.")
        log = label("")
        log.setTextIsSelectable(true)
        setContentView(ScrollView(this).apply { addView(form) })
    }

    override fun onResume() {
        super.onResume()
        refresh?.cancel()
        if (PhoneMicService.ready && !MicSettings.notificationsAllowed(this)) stopService(Intent(this, PhoneMicService::class.java))
        refresh = lifecycleScope.launch { while (isActive) { refreshStatus(); delay(500) } }
    }

    override fun onStop() { refresh?.cancel(); stopTest(); super.onStop() }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        status.text = MicSettings.health(this) + "\nPhone background access: ${if (PhoneMicService.ready) "enabled" else "disabled"}" +
            "\n${if (VoiceDiagnostics.recording) "Recording now" else "Microphone closed"}"
        meter.progress = if (!VoiceDiagnostics.recording || VoiceDiagnostics.liveRms <= 0) 0 else
            ((20 * log10(VoiceDiagnostics.liveRms / 32768.0) + 90) / 90 * 100).toInt().coerceIn(0, 100)
        val report = VoiceDiagnostics.report(this)
        if (log.text.toString() != report) log.text = report
    }

    private fun enablePhone() {
        try {
            // This action runs only from the visible Activity. The service promotes itself
            // immediately; a permission/setup failure can then stop cleanly without an
            // outstanding startForegroundService promotion deadline.
            startService(Intent(this, PhoneMicService::class.java))
            source.check(100 + MicrophoneSource.PHONE.ordinal)
        } catch (e: Exception) {
            VoiceDiagnostics.failure(this, "Enable phone microphone", e)
            result.text = "Could not enable the phone microphone. Check microphone permission and keep Sierra open, then try again."
        }
    }

    private fun stopTest() { testMic?.stop(); test?.cancel() }

    private fun testPhone(transcribe: Boolean) {
        if (test != null) return
        if (!MicSettings.permitted(this)) { pendingEnable = false; permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return }
        result.text = "Speak for five seconds…"
        test = lifecycleScope.launch {
            val mic = Microphones.phone(this@MicDiagnosticsActivity).also { testMic = it }
            val focus = AndroidAudioFocus(this@MicDiagnosticsActivity)
            try {
                val (audio, levels) = runInterruptible(Dispatchers.IO) {
                    if (!focus.request(AudioFocusGate.Mode.LISTEN) { mic.stop() }) throw IllegalStateException("Audio focus denied")
                    val out = ByteArrayOutputStream()
                    val levels = MicLevels(mic.sampleRate)
                    try {
                        mic.start()
                        val buf = ByteArray(1280)
                        while (levels.durationMs < 5_000) {
                            val n = mic.read(buf)
                            if (n < 0) break
                            if (n > 0) { levels.offer(buf, n); out.write(buf, 0, n) }
                        }
                    } finally { mic.stop(); focus.abandon() }
                    out.toByteArray() to levels
                }
                result.text = when {
                    audio.isEmpty() -> "No audio arrived. See diagnostics below."
                    levels.isDigitalSilence -> "Phone microphone delivered silence. Check permission, privacy switch and audio route."
                    else -> "Audio arrived: peak RMS ${levels.peakRms.toInt()}."
                }
                if (transcribe && audio.isNotEmpty() && !levels.isDigitalSilence) {
                    val config = Studio54.agentConfig(this@MicDiagnosticsActivity, ConfigStore.load(this@MicDiagnosticsActivity))
                    val transcriber = VoiceStack.transcriber(VoiceSettingsStore.load(this@MicDiagnosticsActivity), config)
                        ?: throw IllegalStateException("Transcription not configured")
                    VoiceDiagnostics.event(this@MicDiagnosticsActivity, "Phone transcription test", "request sent")
                    result.text = "Audio received. Transcribing…"
                    val text = transcriber.transcribe(audio, mic.sampleRate)
                    VoiceDiagnostics.event(this@MicDiagnosticsActivity, "Phone transcription test", "response received; characters=${text.length}")
                    result.text = if (text.isBlank()) "Server received audio but returned no words." else "Heard: $text\n(This text is not included in diagnostic reports.)"
                }
            } catch (e: CancellationException) { result.text = "Test stopped."; throw e
            } catch (e: Exception) {
                VoiceDiagnostics.failure(this@MicDiagnosticsActivity, "Phone test failed", e)
                result.text = "Test failed: ${VoiceDiagnostics.failureCode(e)}. See diagnostics below."
            } finally { mic.stop(); focus.abandon(); testMic = null; test = null; refreshStatus() }
        }
    }

    private fun share() {
        try {
            val directory = File(cacheDir, "voice-diagnostics").apply { mkdirs() }
            val file = File(directory, "sierra-diagnostics.txt").apply { writeText(VoiceDiagnostics.report(this@MicDiagnosticsActivity)) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("Sierra diagnostics", uri)
            startActivity(Intent.createChooser(intent, "Share Sierra diagnostics"))
        } catch (e: Exception) { result.text = "Could not open sharing. Use Copy diagnostics instead." }
    }
}
