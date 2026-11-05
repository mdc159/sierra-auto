package com.mdc159.sierra

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.voice.convo.VoiceProvider
import com.mdc159.sierra.voice.convo.VoiceSettings
import com.mdc159.sierra.voice.convo.VoiceSettingsStore
import com.mdc159.sierra.voice.convo.VoiceStack
import com.mdc159.sierra.voice.stt.GatewayTranscriber
import com.mdc159.sierra.voice.tts.GatewayTts
import com.mdc159.sierra.voice.tts.SpeakResult
import com.mdc159.sierra.voice.tts.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Phone-side voice settings: which voice reads replies, which model transcribes, whether
 * Talk keeps listening between turns, and the experimental realtime switch. Opened from a
 * button on the main settings screen. Keys are stored encrypted by VoiceSettingsStore.
 */
class VoiceSettingsActivity : Activity() {

    private lateinit var provider: Spinner
    private lateinit var gatewaySection: LinearLayout
    private lateinit var elevenSection: LinearLayout
    private lateinit var gatewayModel: EditText
    private lateinit var gatewayVoice: Spinner
    private lateinit var elevenKey: EditText
    private lateinit var elevenVoice: EditText
    private lateinit var elevenModel: EditText
    private lateinit var sttModel: Spinner
    private lateinit var conversationMode: Switch
    private lateinit var silenceTimeout: EditText
    private lateinit var realtime: Switch
    private lateinit var realtimeModel: EditText
    private lateinit var realtimeVoice: EditText
    private lateinit var testButton: Button
    private lateinit var testResult: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var testJob: Job? = null
    private var testVoice: SpeechOutput? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun section(parent: LinearLayout = form) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun heading(text: String, parent: LinearLayout = form) = parent.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        })
        fun note(text: String, parent: LinearLayout = form) = parent.addView(TextView(this).apply { this.text = text })
        fun field(label: String, hint: String, parent: LinearLayout = form, secret: Boolean = false, number: Boolean = false): EditText {
            parent.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                this.hint = hint
                isSingleLine = true
                inputType = when {
                    secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    number -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT
                }
                parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        fun spinner(label: String, options: List<String>, parent: LinearLayout = form): Spinner {
            parent.addView(TextView(this).apply { text = label })
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@VoiceSettingsActivity, android.R.layout.simple_spinner_dropdown_item, options)
                parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        fun switch(label: String, parent: LinearLayout = form): Switch = Switch(this).apply {
            text = label
            setPadding(0, pad / 2, 0, pad / 2)
            parent.addView(this)
        }

        form.addView(TextView(this).apply {
            text = "Voice"
            textSize = 24f
        })
        note("How Sierra listens and speaks on the truck. The gateway voice and transcription use the agent URL and key from the main screen.")

        heading("Voice")
        provider = spinner("Provider", VoiceProvider.values().map { it.label })
        gatewaySection = section()
        gatewayModel = field("Speech model", GatewayTts.DEFAULT_MODEL, gatewaySection)
        gatewayVoice = spinner("Voice", GatewayTts.VOICES, gatewaySection)
        elevenSection = section()
        elevenKey = field("ElevenLabs API key", "xi-…", elevenSection, secret = true)
        elevenVoice = field("ElevenLabs voice ID", "e.g. 21m00Tcm4TlvDq8ikWAM", elevenSection)
        elevenModel = field("ElevenLabs model", "eleven_flash_v2_5", elevenSection)
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showProviderFields()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        testButton = Button(this).apply {
            text = "Test voice"
            setOnClickListener { testVoice() }
        }
        form.addView(testButton)
        testResult = TextView(this)
        form.addView(testResult)

        heading("Listening")
        sttModel = spinner("Transcription model (on the gateway)", GatewayTranscriber.KNOWN_MODELS)
        conversationMode = switch("Conversation mode: keep listening after each reply")
        silenceTimeout = field("End the conversation after this many seconds of silence", "8", number = true)
        note("Say \"stop\", \"that's all\" or \"never mind\" to end a conversation. Tap the mic while Sierra is talking to interrupt.")

        heading("Experimental")
        realtime = switch("Realtime speech-to-speech through the gateway (off by default)")
        realtimeModel = field("Realtime model", "openai/gpt-realtime-2.1")
        realtimeVoice = field("Realtime voice", "marin")
        note("Unverified on the gateway. See docs/VOICE.md. Leave off unless you are testing it.")

        form.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        })

        setContentView(ScrollView(this).apply { addView(form) })
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        testJob?.cancel()
        testVoice?.close()
        scope.cancel()
    }

    private fun showProviderFields() {
        val p = VoiceProvider.values()[provider.selectedItemPosition]
        gatewaySection.visibility = if (p == VoiceProvider.GATEWAY) View.VISIBLE else View.GONE
        elevenSection.visibility = if (p == VoiceProvider.ELEVENLABS) View.VISIBLE else View.GONE
    }

    private fun load() {
        val s = VoiceSettingsStore.load(this)
        provider.setSelection(s.provider.ordinal)
        gatewayModel.setText(s.gatewayTtsModel)
        gatewayVoice.setSelection(GatewayTts.VOICES.indexOf(s.gatewayVoice).coerceAtLeast(0))
        elevenKey.setText(s.elevenLabsKey)
        elevenVoice.setText(s.elevenLabsVoiceId)
        elevenModel.setText(s.elevenLabsModel)
        sttModel.setSelection(GatewayTranscriber.KNOWN_MODELS.indexOf(s.sttModel).coerceAtLeast(0))
        conversationMode.isChecked = s.conversationMode
        silenceTimeout.setText(s.silenceTimeoutSec.toString())
        realtime.isChecked = s.realtimeEnabled
        realtimeModel.setText(s.realtimeModel)
        realtimeVoice.setText(s.realtimeVoice)
        showProviderFields()
    }

    private fun current(): VoiceSettings = VoiceSettings(
        provider = VoiceProvider.values()[provider.selectedItemPosition],
        gatewayTtsModel = gatewayModel.text.toString().trim().ifBlank { GatewayTts.DEFAULT_MODEL },
        gatewayVoice = GatewayTts.VOICES[gatewayVoice.selectedItemPosition.coerceIn(0, GatewayTts.VOICES.size - 1)],
        elevenLabsKey = elevenKey.text.toString().trim(),
        elevenLabsVoiceId = elevenVoice.text.toString().trim(),
        elevenLabsModel = elevenModel.text.toString().trim().ifBlank { VoiceSettings().elevenLabsModel },
        sttModel = GatewayTranscriber.KNOWN_MODELS[sttModel.selectedItemPosition.coerceIn(0, GatewayTranscriber.KNOWN_MODELS.size - 1)],
        conversationMode = conversationMode.isChecked,
        silenceTimeoutSec = silenceTimeout.text.toString().trim().toIntOrNull()?.coerceIn(2, 60) ?: 8,
        realtimeEnabled = realtime.isChecked,
        realtimeModel = realtimeModel.text.toString().trim().ifBlank { VoiceSettings().realtimeModel },
        realtimeVoice = realtimeVoice.text.toString().trim().ifBlank { VoiceSettings().realtimeVoice },
    )

    private fun save() {
        VoiceSettingsStore.save(this, current())
        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
    }

    /** Saves, then speaks a sample with the chosen voice so the owner hears it before driving. */
    private fun testVoice() {
        val settings = current()
        VoiceSettingsStore.save(this, settings)
        val agent = ConfigStore.load(this)
        if (!settings.providerIsUsable(gatewayConfigured = agent.baseUrl.isNotBlank())) {
            testResult.text = when (settings.provider) {
                VoiceProvider.GATEWAY -> "Enter the agent base URL on the main screen first."
                VoiceProvider.ELEVENLABS -> "Enter the ElevenLabs key and voice ID first."
                VoiceProvider.ANDROID -> "Android voice is always available."
            }
            return
        }
        testJob?.cancel()
        testVoice?.close()
        val voice = VoiceStack.speechOutput(this, settings, agent).also { testVoice = it }
        testButton.isEnabled = false
        testResult.text = "Speaking with ${voice.name}…"
        testJob = scope.launch {
            val result = try {
                voice.speak(VoiceSettings.SAMPLE_TEXT)
            } catch (e: Exception) {
                SpeakResult.Failed(e.message ?: "Voice failed.")
            }
            testResult.text = when (result) {
                SpeakResult.Completed -> "OK: ${voice.name}"
                SpeakResult.Interrupted -> "Stopped."
                is SpeakResult.Failed -> "Failed: ${result.message}"
            }
            testButton.isEnabled = true
            voice.close()
            if (testVoice === voice) testVoice = null
        }
    }
}
