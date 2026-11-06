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
import com.mdc159.sierra.studio54.Studio54
import com.mdc159.sierra.voice.convo.SttProvider
import com.mdc159.sierra.voice.convo.VoiceProvider
import com.mdc159.sierra.voice.convo.VoiceSettings
import com.mdc159.sierra.voice.convo.VoiceSettingsStore
import com.mdc159.sierra.voice.convo.VoiceStack
import com.mdc159.sierra.voice.realtime.RealtimeConfig
import com.mdc159.sierra.voice.stt.ElevenLabsTranscriber
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
 * Phone-side voice settings: which voice reads replies, who transcribes, whether Talk
 * keeps listening between turns, and the experimental realtime switch. Opened from a
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
    private lateinit var sttProvider: Spinner
    private lateinit var sttGatewaySection: LinearLayout
    private lateinit var sttElevenSection: LinearLayout
    private lateinit var sttModel: Spinner
    private lateinit var elevenSttModel: EditText
    private lateinit var conversationMode: Switch
    private lateinit var silenceTimeout: EditText
    private lateinit var realtime: Switch
    private lateinit var realtimeModel: Spinner
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
        fun onSelect(spinner: Spinner, action: () -> Unit) {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        form.addView(TextView(this).apply {
            text = "Voice"
            textSize = 24f
        })
        note("How Sierra listens and speaks on the truck. Gateway voice and transcription use the agent URL and key from the main screen.")

        heading("ElevenLabs (optional)")
        note("Fastest voice and transcription measured so far. Leave blank to use the gateway only.")
        elevenKey = field("ElevenLabs API key", "xi-…", secret = true)

        heading("Voice")
        provider = spinner("Provider", VoiceProvider.values().map { it.label })
        gatewaySection = section()
        gatewayModel = field("Speech model", GatewayTts.DEFAULT_MODEL, gatewaySection)
        gatewayVoice = spinner("Voice", GatewayTts.VOICES, gatewaySection)
        elevenSection = section()
        elevenVoice = field("ElevenLabs voice ID", "from the Voices page", elevenSection)
        elevenModel = field("ElevenLabs model", "eleven_flash_v2_5 (fastest), eleven_v4_turbo", elevenSection)
        onSelect(provider) { showProviderFields() }
        testButton = Button(this).apply {
            text = "Test voice"
            setOnClickListener { testVoice() }
        }
        form.addView(testButton)
        testResult = TextView(this)
        form.addView(testResult)

        heading("Listening")
        sttProvider = spinner("Transcription", SttProvider.values().map { it.label })
        sttGatewaySection = section()
        sttModel = spinner("Gateway transcription model", GatewayTranscriber.KNOWN_MODELS, sttGatewaySection)
        sttElevenSection = section()
        elevenSttModel = field("ElevenLabs transcription model", ElevenLabsTranscriber.DEFAULT_MODEL, sttElevenSection)
        onSelect(sttProvider) { showProviderFields() }
        conversationMode = switch("Conversation mode: keep listening after each reply")
        silenceTimeout = field("End the conversation after this many seconds of silence", "8", number = true)
        note("Say \"stop\", \"that's all\" or \"never mind\" to end a conversation. Tap the mic while Sierra is talking to interrupt.")

        heading("Experimental")
        realtime = switch("Realtime speech-to-speech through the gateway (off by default)")
        realtimeModel = spinner("Realtime model", RealtimeConfig.KNOWN_MODELS)
        realtimeVoice = field("Realtime voice", RealtimeConfig.DEFAULT_VOICE)
        note("The route works from a PC (issue #39); nothing has run from the phone. Tool calls do not work on this path yet. See docs/VOICE.md.")

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
        val s = SttProvider.values()[sttProvider.selectedItemPosition]
        sttGatewaySection.visibility = if (s == SttProvider.GATEWAY) View.VISIBLE else View.GONE
        sttElevenSection.visibility = if (s == SttProvider.ELEVENLABS) View.VISIBLE else View.GONE
    }

    private fun load() {
        val s = VoiceSettingsStore.load(this)
        provider.setSelection(s.provider.ordinal)
        gatewayModel.setText(s.gatewayTtsModel)
        gatewayVoice.setSelection(GatewayTts.VOICES.indexOf(s.gatewayVoice).coerceAtLeast(0))
        elevenKey.setText(s.elevenLabsKey)
        elevenVoice.setText(s.elevenLabsVoiceId)
        elevenModel.setText(s.elevenLabsModel)
        sttProvider.setSelection(s.sttProvider.ordinal)
        sttModel.setSelection(GatewayTranscriber.KNOWN_MODELS.indexOf(s.sttModel).coerceAtLeast(0))
        elevenSttModel.setText(s.elevenLabsSttModel)
        conversationMode.isChecked = s.conversationMode
        silenceTimeout.setText(s.silenceTimeoutSec.toString())
        realtime.isChecked = s.realtimeEnabled
        realtimeModel.setSelection(RealtimeConfig.KNOWN_MODELS.indexOf(s.realtimeModel).coerceAtLeast(0))
        realtimeVoice.setText(s.realtimeVoice)
        showProviderFields()
    }

    private fun current(): VoiceSettings {
        val defaults = VoiceSettings()
        return VoiceSettings(
            provider = VoiceProvider.values()[provider.selectedItemPosition],
            gatewayTtsModel = gatewayModel.text.toString().trim().ifBlank { GatewayTts.DEFAULT_MODEL },
            gatewayVoice = GatewayTts.VOICES[gatewayVoice.selectedItemPosition.coerceIn(0, GatewayTts.VOICES.size - 1)],
            elevenLabsKey = elevenKey.text.toString().trim(),
            elevenLabsVoiceId = elevenVoice.text.toString().trim(),
            elevenLabsModel = elevenModel.text.toString().trim().ifBlank { defaults.elevenLabsModel },
            sttProvider = SttProvider.values()[sttProvider.selectedItemPosition],
            sttModel = GatewayTranscriber.KNOWN_MODELS[sttModel.selectedItemPosition.coerceIn(0, GatewayTranscriber.KNOWN_MODELS.size - 1)],
            elevenLabsSttModel = elevenSttModel.text.toString().trim().ifBlank { defaults.elevenLabsSttModel },
            conversationMode = conversationMode.isChecked,
            silenceTimeoutSec = silenceTimeout.text.toString().trim().toIntOrNull()?.coerceIn(2, 60) ?: defaults.silenceTimeoutSec,
            realtimeEnabled = realtime.isChecked,
            realtimeModel = RealtimeConfig.KNOWN_MODELS[realtimeModel.selectedItemPosition.coerceIn(0, RealtimeConfig.KNOWN_MODELS.size - 1)],
            realtimeVoice = realtimeVoice.text.toString().trim().ifBlank { defaults.realtimeVoice },
        )
    }

    private fun save() {
        val ok = VoiceSettingsStore.save(this, current())
        Toast.makeText(this, if (ok) "Saved." else "Couldn't open the encrypted settings store. Try again.", Toast.LENGTH_LONG).show()
    }

    /** Saves, then speaks a sample with the chosen voice so the owner hears it before driving. */
    private fun testVoice() {
        val settings = current()
        VoiceSettingsStore.save(this, settings)
        // Signed in to Studio 54, the vault fills the gateway URL and key; the typed ones may be blank.
        val agent = Studio54.agentConfig(this, ConfigStore.load(this))
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
        val started = System.currentTimeMillis()
        testJob = scope.launch {
            val result = try {
                voice.speak(VoiceSettings.SAMPLE_TEXT)
            } catch (e: Exception) {
                SpeakResult.Failed(e.message ?: "Voice failed.")
            }
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            testResult.text = when (result) {
                SpeakResult.Completed -> "OK: ${voice.name} (%.1f s including playback)".format(seconds)
                SpeakResult.Interrupted -> "Stopped."
                is SpeakResult.Failed -> "Failed: ${result.message}"
            }
            testButton.isEnabled = true
            voice.close()
            if (testVoice === voice) testVoice = null
        }
    }
}
