package com.mdc159.sierra.voice.duplex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import com.mdc159.sierra.BuildConfig
import com.mdc159.sierra.studio54.AgentOverlay
import com.mdc159.sierra.studio54.Studio54

/**
 * Phone-side settings for the full-duplex conversation, plus "Try it on this phone",
 * which runs a live session with the phone's microphone and speaker so the owner can
 * test tonight without the truck.
 *
 * Bench builds only (`BuildConfig.BENCH_BUILD`, the debug app): the screen can be driven
 * from adb with the `bench` intent extra, see "Driving the phone test from adb" in
 * docs/DUPLEX.md. Release builds ignore the extra and do not export this activity.
 */
class DuplexSettingsActivity : ComponentActivity() {

    private lateinit var providerGroup: RadioGroup
    private lateinit var modeGroup: RadioGroup
    private lateinit var vaultNote: TextView
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

    /** The controller whose stop is already running on its own thread, so it is asked once. */
    private var stopping: DuplexController? = null

    /** True once the current session finished connecting (typed turns can be sent). */
    private var live = false

    /** Bench: a typed turn that arrived before the session was live. */
    private var pendingText: String? = null
    private val lines = ArrayDeque<String>()

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                tryIt()
            } else {
                DuplexLog.w("screen: microphone permission refused")
                setStatus("Microphone permission is required.")
            }
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
        vaultNote = TextView(this).apply {
            text = "Base URL and API key come from the Studio 54 vault. Leave the model blank for ${AgentOverlay.GATEWAY_REALTIME_MODEL}."
        }
        form.addView(vaultNote)
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
            setOnClickListener {
                save()
                val message = if (DuplexConfigStore.openError == null) "Saved." else "Not saved: the settings store could not be opened. Kept until the app closes."
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
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
            setOnClickListener { stopSession("Stop button") }
        }
        form.addView(stopButton)
        status = TextView(this)
        form.addView(status)
        transcript = TextView(this).apply { setPadding(0, pad / 2, 0, pad) }
        form.addView(transcript)

        setContentView(ScrollView(this).apply { addView(form) })
        load()
        if (BuildConfig.BENCH_BUILD) {
            // The session ends when the screen goes off (onStop); keep it on while benching.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Not after a rotation: that would replay the command the screen was opened with.
            if (savedInstanceState == null) handleBench(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleBench(intent)
    }

    /**
     * Bench builds only: `--es bench start|say|interrupt|stop|instructions|config`, with
     * `--es text "…"` for a typed turn (on `start` it is sent as soon as the session is live).
     * Same code paths as the buttons; nothing here runs in a release build.
     */
    private fun handleBench(intent: Intent?) {
        if (!BuildConfig.BENCH_BUILD) return
        val command = intent?.getStringExtra(EXTRA_BENCH)?.trim()?.lowercase() ?: return
        val text = intent.getStringExtra(EXTRA_BENCH_TEXT).orEmpty().trim()
        DuplexLog.i("bench command=$command textChars=${text.length} session=${controller != null} live=$live")
        when (command) {
            "start" -> {
                if (text.isNotEmpty()) pendingText = text
                if (controller == null) ensureMicThen { tryIt() } else flushPendingText()
            }
            "say" -> when {
                text.isEmpty() -> DuplexLog.w("bench say ignored: no text extra")
                controller == null -> DuplexLog.w("bench say ignored: no session; send bench start first")
                else -> {
                    pendingText = text
                    flushPendingText()
                }
            }
            "interrupt" -> controller?.tapInterrupt() ?: DuplexLog.w("bench interrupt ignored: no session")
            "stop" -> if (controller != null) stopSession("bench") else DuplexLog.w("bench stop ignored: no session")
            "instructions" -> benchInstructions()
            "config" -> benchConfig(intent)
            else -> DuplexLog.w("bench command not recognised")
        }
    }

    /**
     * Bench: loads the system prompt from `files/duplex-instructions.txt` in this app's private
     * files dir (written with `adb shell run-as`), saves it and logs only its length.
     */
    private fun benchInstructions() {
        val file = java.io.File(filesDir, BENCH_INSTRUCTIONS_FILE)
        val text = try {
            file.readText(Charsets.UTF_8).trim()
        } catch (e: Exception) {
            DuplexLog.w("bench instructions not loaded: ${e.javaClass.simpleName} reading files/$BENCH_INSTRUCTIONS_FILE")
            return
        }
        if (text.isEmpty()) {
            DuplexLog.w("bench instructions not loaded: files/$BENCH_INSTRUCTIONS_FILE is empty")
            return
        }
        instructions.setText(text)
        save()
        val stored = DuplexConfigStore.load(this).instructions
        DuplexLog.i("bench instructions saved chars=${text.length} storedChars=${stored.length} match=${stored == text} storeError=${DuplexConfigStore.openError != null}")
    }

    /**
     * Bench: `--es provider openai|elevenlabs`, `--es mode full|guarded`, `--es voice NAME`;
     * each extra is optional, the rest of the form is saved as it stands.
     */
    private fun benchConfig(intent: Intent) {
        when (val v = intent.getStringExtra("provider")?.trim()?.lowercase()) {
            null -> Unit
            "openai", "elevenlabs" -> providerGroup.check(if (v == "openai") 1 else 2)
            else -> DuplexLog.w("bench config: provider not recognised (openai|elevenlabs)")
        }
        when (val v = intent.getStringExtra("mode")?.trim()?.lowercase()) {
            null -> Unit
            "full", "guarded" -> modeGroup.check(if (v == "guarded") 1 else 2)
            else -> DuplexLog.w("bench config: mode not recognised (full|guarded)")
        }
        intent.getStringExtra("voice")?.trim()?.takeIf { it.isNotEmpty() }?.let { openAiVoice.setText(it) }
        save()
        val c = DuplexConfigStore.load(this)
        DuplexLog.i("bench config saved provider=${c.provider} mode=${c.mode} voice=${c.openAiVoice} model=${c.openAiModel} instructionsChars=${c.instructions.length} storeError=${DuplexConfigStore.openError != null}")
    }

    /** Sends the waiting typed turn through the session's own `sendText`, once it is live. */
    private fun flushPendingText() {
        val c = controller ?: return
        if (!live) {
            DuplexLog.i("bench typed turn held until the session is live")
            return
        }
        val text = pendingText ?: return
        pendingText = null
        DuplexLog.i("screen: typed turn: $text")
        addLine("You (typed): $text")
        c.sendText(text)
    }

    override fun onResume() {
        super.onResume()
        showVaultHints()
    }

    /**
     * When the vault supplies the gateway (Studio54.duplexConfig), the typed base URL and key
     * are not used, and the built-in OpenAI model name becomes the gateway's realtime model.
     */
    private fun showVaultHints() {
        val vault = Studio54.suppliesAgent(this)
        vaultNote.visibility = if (vault) View.VISIBLE else View.GONE
        openAiUrl.hint = if (vault) "From Studio 54 vault" else DuplexConfig.DEFAULT_OPENAI_BASE_URL
        openAiKey.hint = if (vault) "From Studio 54 vault" else "sk-…"
        openAiModel.hint = if (vault) AgentOverlay.GATEWAY_REALTIME_MODEL else DuplexConfig.DEFAULT_OPENAI_MODEL
        if (vault) {
            // Built-in defaults would hide the hints; blank fields save as the same defaults.
            if (openAiUrl.text.toString() == DuplexConfig.DEFAULT_OPENAI_BASE_URL) openAiUrl.setText("")
            if (openAiModel.text.toString() == DuplexConfig.DEFAULT_OPENAI_MODEL) openAiModel.setText("")
        }
    }

    override fun onStop() {
        super.onStop()
        stopSession("screen left")
    }

    /**
     * Ends the session without blocking the screen: closing the microphone and the speaker
     * are native calls, made under the lock a session that is still starting also holds.
     * The controller reports `Ended` when it is done, which re-enables the buttons. Safe to
     * call twice, and when the activity is going away (the thread only holds the controller).
     */
    private fun stopSession(why: String) {
        val c = controller ?: return
        if (stopping === c) return
        stopping = c
        DuplexLog.i("screen: stop requested ($why)")
        stopButton.isEnabled = false
        interruptButton.isEnabled = false
        setStatus("Stopping…")
        Thread({ c.stop() }, "duplex-stop").start()
    }

    private fun load() {
        val c = DuplexConfigStore.load(this)
        DuplexConfigStore.openError?.let {
            setStatus("The saved full-duplex settings could not be opened ($it). Nothing was deleted; defaults are used until the app is restarted.")
        }
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
        val typed = current()
        val config = Studio54.duplexConfig(this, typed)
        DuplexLog.i("screen: try it; provider=${config.provider} mode=${config.mode} model=${config.openAiModel} vault=${Studio54.suppliesAgent(this)} keyPresent=${config.openAiApiKey.isNotBlank()} configured=${config.isConfigured}")
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
        val app = applicationContext
        // Asked again on every connect, so a key the vault refreshed after a 401 is used.
        val session = Duplex.session(config, Duplex.defaultTools(this), credentials = { Studio54.duplexConfig(app, typed) })
        lateinit var c: DuplexController
        c = DuplexController(
            session = session,
            mic = com.mdc159.sierra.voice.diagnostics.Microphones.phone(this),
            sink = AudioTrackSink(),
            focus = AndroidFocus(this),
            mode = config.mode,
            guardRatio = config.guardRatio,
            listener = { event ->
                logEvent(event)
                if (event is DuplexEvent.Error) Studio54.onDuplexError(this, config.provider, event.message)
                // Events of a session this screen has already let go of are not shown.
                runOnUiThread { if (controller === c && !isDestroyed) onEvent(event) }
            },
            log = DuplexLog::i,
        )
        controller = c
        Thread({
            try {
                c.start()
                runOnUiThread {
                    if (controller !== c || !c.isRunning) return@runOnUiThread
                    setStatus("Listening. Say something; talk over Sierra to interrupt.")
                    interruptButton.isEnabled = true
                    live = true
                    flushPendingText()
                }
            } catch (e: DuplexException) {
                DuplexLog.w("screen: couldn't start: ${DuplexLog.redact(e.message)}")
                Studio54.onDuplexError(this@DuplexSettingsActivity, config.provider, e.message)
                runOnUiThread {
                    if (controller !== c) return@runOnUiThread
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

    /** The conversation as text, for logcat. Called on service threads. */
    private fun logEvent(event: DuplexEvent) {
        when (event) {
            is DuplexEvent.UserTranscript -> if (event.final) DuplexLog.i("heard: ${event.text}")
            is DuplexEvent.AssistantTranscript -> if (event.final) DuplexLog.i("said: ${event.text}")
            is DuplexEvent.ToolCall -> DuplexLog.i("screen: tool call ${event.name} args=${DuplexLog.redact(event.argumentsJson, 160)}")
            is DuplexEvent.ToolResult -> DuplexLog.i("screen: tool result ${event.name} error=${event.isError} chars=${event.result.length}")
            is DuplexEvent.Error -> DuplexLog.w("screen: error fatal=${event.fatal}: ${DuplexLog.redact(event.message)}")
            is DuplexEvent.Ended -> DuplexLog.i("screen: ended: ${DuplexLog.redact(event.reason)}")
            is DuplexEvent.Connected -> DuplexLog.i("screen: connected")
            else -> Unit
        }
    }

    private fun finished() {
        controller = null
        stopping = null
        live = false
        pendingText = null
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

    private companion object {
        /** Bench builds: the command (`start`, `say`, `interrupt`, `stop`). */
        const val EXTRA_BENCH = "bench"

        /** Bench builds: the typed turn for `start` or `say`. */
        const val EXTRA_BENCH_TEXT = "text"

        /** Bench builds: `bench instructions` reads this file from the app's files dir. */
        const val BENCH_INSTRUCTIONS_FILE = "duplex-instructions.txt"
    }
}
