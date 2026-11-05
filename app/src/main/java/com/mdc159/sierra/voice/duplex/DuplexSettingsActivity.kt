package com.mdc159.sierra.voice.duplex

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Phone-side settings for the full-duplex conversation, plus "Try it on this phone",
 * which runs a live session with the phone's microphone and speaker so the owner can
 * test tonight without the truck.
 */
class DuplexSettingsActivity : ComponentActivity() {

    private lateinit var providerGroup: RadioGroup
    private lateinit var modeGroup: RadioGroup
    private lateinit var openAiUrl: EditText
    private lateinit var openAiKey: EditText
    private lateinit var openAiModel: EditText
    private lateinit var openAiVoice: EditText
    private lateinit var openAiStt: EditText
    private lateinit var elevenKey: EditText
    private lateinit var elevenAgent: EditText
    private lateinit var elevenVoice: EditText
    private lateinit var instructions: EditText
    private lateinit var guardRatio: EditText
    private lateinit var tryButton: Button
    private lateinit var interruptButton: Button
    private lateinit var stopButton: Button
    private lateinit var status: TextView
    private lateinit var transcript: TextView

    private var controller: DuplexController? = null
    private val lines = ArrayDeque<String>()

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tryIt() else setStatus("Microphone permission is required.")
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
        fun note(text: String) = form.addView(TextView(this).apply { this.text = text })
        fun field(label: String, hint: String, secret: Boolean = false, multiline: Boolean = false): EditText {
            form.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                this.hint = hint
                isSingleLine = !multiline
                inputType = when {
                    secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                form.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        fun radios(options: List<String>): RadioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            options.forEachIndexed { i, label -> addView(RadioButton(this@DuplexSettingsActivity).apply { text = label; id = i + 1 }) }
            form.addView(this)
        }

        form.addView(TextView(this).apply {
            text = "Full-duplex voice"
            textSize = 24f
        })
        note("A live two-way conversation: talk over Sierra and it stops. See docs/DUPLEX.md.")

        heading("Provider")
        providerGroup = radios(listOf("OpenAI Realtime (OpenAI or the gateway)", "ElevenLabs Agents"))

        heading("Microphone while Sierra speaks")
        modeGroup = radios(listOf("Guarded: ignore the mic unless you talk over it (truck)", "Full duplex: always listening (headset or quiet cab)"))
        guardRatio = field("Guarded: how much louder you must be than the playback (x)", "2.0")

        heading("OpenAI Realtime")
        openAiUrl = field("Base URL", DuplexConfig.DEFAULT_OPENAI_BASE_URL)
        openAiKey = field("API key", "sk-…", secret = true)
        openAiModel = field("Model (gateway: openai/gpt-realtime-2.1-mini)", DuplexConfig.DEFAULT_OPENAI_MODEL)
        openAiVoice = field("Voice", DuplexConfig.DEFAULT_OPENAI_VOICE)
        openAiStt = field("Transcription model (blank = none)", DuplexConfig.DEFAULT_OPENAI_TRANSCRIPTION_MODEL)

        heading("ElevenLabs Agents")
        note("Create an agent in the ElevenLabs dashboard first, with PCM audio and the ask_agent client tool. Steps are in docs/DUPLEX.md.")
        elevenKey = field("API key (blank for a public agent)", "", secret = true)
        elevenAgent = field("Agent ID", "agent_…")
        elevenVoice = field("Voice ID override (optional)", "")

        heading("Instructions")
        instructions = field("System prompt", DuplexConfig.DEFAULT_INSTRUCTIONS, multiline = true)

        form.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save(); Toast.makeText(context, "Saved.", Toast.LENGTH_SHORT).show() }
        })

        heading("Try it on this phone")
        note("Uses the phone microphone (with Android's echo canceller) and speaker. Hold the phone like a call, or use the loudspeaker to rehearse the truck's echo problem.")
        tryButton = Button(this).apply {
            text = "Try it on this phone"
            setOnClickListener { save(); ensureMicThen { tryIt() } }
        }
        form.addView(tryButton)
        interruptButton = Button(this).apply {
            text = "Interrupt (tap)"
            isEnabled = false
            setOnClickListener { controller?.tapInterrupt() }
        }
        form.addView(interruptButton)
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { controller?.stop() }
        }
        form.addView(stopButton)
        status = TextView(this)
        form.addView(status)
        transcript = TextView(this).apply { setPadding(0, pad / 2, 0, pad) }
        form.addView(transcript)

        setContentView(ScrollView(this).apply { addView(form) })
        load()
    }

    override fun onStop() {
        super.onStop()
        controller?.stop()
    }

    private fun load() {
        val c = DuplexConfigStore.load(this)
        providerGroup.check(if (c.provider == DuplexProvider.OPENAI_REALTIME) 1 else 2)
        modeGroup.check(if (c.mode == DuplexMode.GUARDED) 1 else 2)
        guardRatio.setText(c.guardRatio.toString())
        openAiUrl.setText(c.openAiBaseUrl)
        openAiKey.setText(c.openAiApiKey)
        openAiModel.setText(c.openAiModel)
        openAiVoice.setText(c.openAiVoice)
        openAiStt.setText(c.openAiTranscriptionModel)
        elevenKey.setText(c.elevenApiKey)
        elevenAgent.setText(c.elevenAgentId)
        elevenVoice.setText(c.elevenVoiceId)
        instructions.setText(c.instructions)
    }

    private fun current(): DuplexConfig = DuplexConfig(
        provider = if (providerGroup.checkedRadioButtonId == 2) DuplexProvider.ELEVENLABS else DuplexProvider.OPENAI_REALTIME,
        mode = if (modeGroup.checkedRadioButtonId == 2) DuplexMode.FULL_DUPLEX else DuplexMode.GUARDED,
        openAiBaseUrl = openAiUrl.text.toString().trim().ifBlank { DuplexConfig.DEFAULT_OPENAI_BASE_URL },
        openAiApiKey = openAiKey.text.toString().trim(),
        openAiModel = openAiModel.text.toString().trim().ifBlank { DuplexConfig.DEFAULT_OPENAI_MODEL },
        openAiVoice = openAiVoice.text.toString().trim(),
        openAiTranscriptionModel = openAiStt.text.toString().trim(),
        elevenApiKey = elevenKey.text.toString().trim(),
        elevenAgentId = elevenAgent.text.toString().trim(),
        elevenVoiceId = elevenVoice.text.toString().trim(),
        instructions = instructions.text.toString().trim().ifBlank { DuplexConfig.DEFAULT_INSTRUCTIONS },
        guardRatio = guardRatio.text.toString().trim().toDoubleOrNull()?.takeIf { it >= 1.0 } ?: 2.0,
    )

    private fun save() = DuplexConfigStore.save(this, current())

    private fun ensureMicThen(block: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            block()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun tryIt() {
        if (controller != null) return
        val config = current()
        if (!config.isConfigured) {
            setStatus(
                if (config.provider == DuplexProvider.ELEVENLABS) "Enter an ElevenLabs agent ID first."
                else "Enter a base URL and model first.",
            )
            return
        }
        lines.clear()
        transcript.text = ""
        setStatus("Connecting…")
        tryButton.isEnabled = false
        stopButton.isEnabled = true
        val session = Duplex.session(config, Duplex.defaultTools(this))
        val c = DuplexController(
            session = session,
            mic = PhoneMic(),
            sink = AudioTrackSink(),
            focus = AndroidFocus(this),
            mode = config.mode,
            guardRatio = config.guardRatio,
            listener = { event -> runOnUiThread { onEvent(event) } },
        )
        controller = c
        Thread({
            try {
                c.start()
                runOnUiThread {
                    setStatus("Listening. Say something; talk over Sierra to interrupt.")
                    interruptButton.isEnabled = true
                }
            } catch (e: DuplexException) {
                runOnUiThread {
                    setStatus("Couldn't start: ${e.message}")
                    finished()
                }
            }
        }, "duplex-start").start()
    }

    private fun onEvent(event: DuplexEvent) {
        when (event) {
            is DuplexEvent.Connected -> setStatus("Connected to ${event.provider.name.lowercase().replace('_', ' ')}.")
            is DuplexEvent.Reconnecting -> setStatus("Reconnecting (${event.attempt})… ${event.reason}")
            is DuplexEvent.UserTranscript -> if (event.final) addLine("You: ${event.text}") else setStatus("You: ${event.text}")
            is DuplexEvent.AssistantTranscript -> if (event.final) addLine("Sierra: ${event.text}") else setStatus("Sierra: ${event.text}")
            is DuplexEvent.ToolCall -> addLine("→ ${event.name}(${event.argumentsJson.take(120)})")
            is DuplexEvent.ToolResult -> addLine("← ${event.name}: ${event.result.take(160)}")
            is DuplexEvent.UserStartedSpeaking -> setStatus("Heard you, stopping…")
            is DuplexEvent.UserStoppedSpeaking -> setStatus("Thinking…")
            is DuplexEvent.AssistantDone -> setStatus(if (event.cancelled) "Interrupted." else "Listening…")
            is DuplexEvent.Error -> setStatus("Error: ${event.message}")
            is DuplexEvent.Ended -> {
                setStatus("Ended: ${event.reason}")
                finished()
            }
            is DuplexEvent.AssistantAudio -> Unit
        }
    }

    private fun finished() {
        controller = null
        tryButton.isEnabled = true
        interruptButton.isEnabled = false
        stopButton.isEnabled = false
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun addLine(line: String) {
        lines.addLast(line)
        while (lines.size > 40) lines.removeFirst()
        transcript.text = lines.joinToString("\n")
    }
}
