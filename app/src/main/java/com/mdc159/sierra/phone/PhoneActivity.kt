package com.mdc159.sierra.phone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.MainActivity
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentClient
import com.mdc159.sierra.agent.AgentProfile
import com.mdc159.sierra.agent.ChatMessage
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.agent.Conversation
import com.mdc159.sierra.agent.History
import com.mdc159.sierra.net.Http
import com.mdc159.sierra.studio54.RefreshResult
import com.mdc159.sierra.studio54.Studio54
import com.mdc159.sierra.voice.MicLog
import com.mdc159.sierra.voice.PhoneRecognizer
import com.mdc159.sierra.voice.convo.ConversationAgent
import com.mdc159.sierra.voice.convo.ConversationConfig
import com.mdc159.sierra.voice.convo.ConversationController
import com.mdc159.sierra.voice.convo.ConversationState
import com.mdc159.sierra.voice.convo.VoiceSettings
import com.mdc159.sierra.voice.convo.VoiceSettingsStore
import com.mdc159.sierra.voice.convo.VoiceStack
import com.mdc159.sierra.voice.diagnostics.Microphones
import com.mdc159.sierra.voice.diagnostics.VoiceDiagnostics
import com.mdc159.sierra.voice.stt.Transcriber
import com.mdc159.sierra.voice.tts.AndroidAudioFocus
import com.mdc159.sierra.voice.tts.SpeakResult
import com.mdc159.sierra.voice.tts.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's home screen: talk hands-free with the configured agent — normally the real
 * Hermes agent on Victoria through the Sierra bridge — or type a message. The truck's car
 * app is unchanged; MainActivity stays as the settings screen this one links to.
 *
 * Voice runs [ConversationController] (continuous listen → think → speak, stop phrases,
 * barge-in) over the phone microphone, exactly like the truck's Talk screen, but with the
 * phone's own conversation identity so bridge sessions never mix. When no transcription
 * endpoint is configured, a follow-up loop uses the phone's system speech recognizer
 * instead. Text sends share the same agent, history and rolling context, so the input box
 * is a real alternative way to talk, not a separate feature.
 *
 * Everything heavy is injected through [deps] so behavior is unit-testable; the defaults
 * are the production pieces (ConfigStore + Studio 54 overlay, AgentClient with the
 * conversation identity header, VoiceStack, Microphones, PhoneRecognizer).
 */
class PhoneActivity : ComponentActivity() {

    /** Test seam: replace before onCreate (Robolectric sets it on the pre-setup instance). */
    internal var deps: PhoneDeps = PhoneDeps()

    /**
     * Conversation state that survives recreation (rotation, theme change): the rolling
     * context with its bridge identity, the visible transcript and the endpoint key.
     * Retrieved lazily so it is first touched from onCreate, after the activity is attached.
     */
    private val model: PhoneViewModel by lazy { ViewModelProvider(this)[PhoneViewModel::class.java] }
    private val conversation get() = model.conversation
    private val transcript get() = model.transcript
    internal val gate = PhoneJobGate()

    private var voiceJob: Job? = null
    private var textJob: Job? = null
    private var checkJob: Job? = null
    private var controller: ConversationController? = null
    private var speech: SpeechOutput? = null

    /** What the current speech engine was built from, so settings changes rebuild it. */
    private var speechKey: Pair<VoiceSettings, AgentConfig>? = null

    /** True while a reply is being spoken (controller path or recognizer path). */
    @Volatile
    private var speakingNow = false

    @Volatile
    private var pendingTalkAfterPermission = false

    internal lateinit var agentLine: TextView
        private set
    internal lateinit var hintLine: TextView
        private set
    internal lateinit var statusView: TextView
        private set
    internal lateinit var transcriptView: TextView
        private set
    private lateinit var transcriptScroll: ScrollView
    internal lateinit var talkButton: Button
        private set
    internal lateinit var interruptButton: Button
        private set
    internal lateinit var checkButton: Button
        private set
    internal lateinit var settingsButton: Button
        private set
    internal lateinit var tailscaleButton: Button
        private set
    internal lateinit var input: EditText
        private set
    internal lateinit var sendButton: Button
        private set

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingTalkAfterPermission) {
                pendingTalkAfterPermission = false
                startVoice()
            } else if (!granted) {
                status("Microphone permission is needed to talk. Tap Talk to allow it.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        // Everything scrolls as one column (large fonts and the keyboard never clip the
        // buttons); the transcript block scrolls on its own inside it.
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(TextView(this).apply {
            text = "Sierra"
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Talk to your agent from the phone."
            setPadding(0, 0, 0, pad / 2)
        })
        agentLine = TextView(this).apply { textSize = 16f }
        content.addView(agentLine)
        hintLine = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
            visibility = View.GONE
        }
        content.addView(hintLine)

        transcriptView = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        transcriptScroll = ScrollView(this).apply { addView(transcriptView) }
        content.addView(
            transcriptScroll,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.40).toInt()),
        )

        statusView = TextView(this).apply {
            text = PhoneStatus.IDLE
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, pad / 2, 0, pad / 2)
        }
        content.addView(statusView)

        talkButton = Button(this).apply {
            text = "Talk"
            textSize = 20f
            minimumHeight = pad * 4
            setOnClickListener { onTalkClicked() }
        }
        content.addView(talkButton)

        fun row(vararg buttons: Button): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                content.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

        interruptButton = Button(this).apply {
            text = "Interrupt"
            isEnabled = false
            setOnClickListener { interruptSpeech() }
        }
        checkButton = Button(this).apply {
            text = "Check connection"
            setOnClickListener { checkConnection() }
        }
        row(interruptButton, checkButton)

        settingsButton = Button(this).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@PhoneActivity, MainActivity::class.java)) }
        }
        tailscaleButton = Button(this).apply {
            text = "Open Tailscale"
            setOnClickListener { openTailscale() }
        }
        row(settingsButton, tailscaleButton)

        input = EditText(this).apply {
            hint = "Type a message instead of talking"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onSend()
                    true
                } else {
                    false
                }
            }
        }
        sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { onSend() }
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton)
            content.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Put the primary actions above the transcript: a new conversation must not
        // require scrolling past an empty panel to find Talk, even with large text.
        content.removeView(transcriptScroll)
        content.addView(transcriptScroll)

        val root = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        // Edge-to-edge (enforced on API 35+): pad the whole screen by the system bars,
        // cutout and IME so large fonts and the keyboard never cover Talk or the input.
        val baseLeft = pad
        val baseTop = pad * 2
        val baseRight = pad
        val baseBottom = pad
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        refreshButtons()
        renderTranscript()
    }

    override fun onStart() {
        super.onStart()
        Studio54.start(this)
        refreshAgentCard()
        refreshButtons()
    }

    override fun onStop() {
        super.onStop()
        stopEverything("Stopped — Sierra went to the background.")
    }

    override fun onDestroy() {
        stopEverything("Stopped.")
        speech?.let { runCatching { it.close() } }
        speech = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- settings and identity

    /**
     * The effective config: the saved one with the Studio 54 vault overlay. When signed in,
     * refresh the vault first if the last read is stale, so a rotated bridge token is
     * picked up before a turn, not after a failure. A failed refresh keeps the cached
     * values and the typed settings; nothing is deleted.
     */
    private suspend fun effectiveConfig(forceRefresh: Boolean = false): AgentConfig {
        if (deps.signedIn(this)) {
            val fetched = deps.lastFetched(this)
            val stale = fetched == null || System.currentTimeMillis() - fetched > REFRESH_STALE_MS
            if (forceRefresh || stale) {
                try {
                    withTimeoutOrNull(REFRESH_TIMEOUT_MS) { deps.refresh(this@PhoneActivity) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the previously cached credentials when refresh is unavailable.
                }
            }
        }
        return runCatching { deps.config(this) }.getOrElse { AgentConfig() }
    }

    /**
     * Binds the rolling context to the endpoint that is about to answer. Switching between
     * the bridge and a model, or to a different model/host, starts a fresh context (and a
     * fresh bridge conversation identity) so replies from one agent are never answered by
     * another. The displayed transcript stays, with a separator.
     */
    private fun prepareConversation(config: AgentConfig) {
        val key = "${config.profile.name}|${config.chatBaseUrl}|${config.chatModel}"
        val old = model.endpointKey
        if (old != null && old != key) {
            conversation.clear()
            transcript.event("Switched to ${Tailnet.agentLabel(config)}")
            renderTranscript()
            VoiceDiagnostics.event(this, "Phone", "endpoint changed; context cleared")
        }
        model.endpointKey = key
    }

    /**
     * One speech output for the current voice settings and endpoint. A settings change
     * (provider, voice, model or agent URL) releases the old engine and builds a new one,
     * so the screen never keeps speaking through stale credentials or voices.
     */
    private fun ensureSpeech(config: AgentConfig): SpeechOutput {
        val voice = deps.voiceSettings(this)
        val key = voice to config
        speech?.let {
            if (speechKey == key) return it
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        speech = null
        speechKey = null
        val s = deps.speech(this, voice, config)
        speech = s
        speechKey = key
        return s
    }

    /** The shared turn: context in, reply out; history and transcript updated, reply spoken. */
    private suspend fun askAgent(config: AgentConfig, userText: String): String {
        transcript.user(userText)
        renderTranscript()
        val reply = deps.chat(config, conversation.id, conversation.messagesFor(userText))
        conversation.record(userText, reply)
        transcript.agent(reply)
        renderTranscript()
        deps.history(this, userText, reply)
        return Conversation.forSpeech(reply)
    }

    // ---------------------------------------------------------------- voice conversation

    /** The primary button: Talk when idle, Stop while any job runs — voice, typed or check. */
    private fun onTalkClicked() {
        when (gate.active) {
            PhoneJob.VOICE -> stopVoice("Stopped.")
            PhoneJob.TEXT -> {
                textJob?.cancel()
                speech?.stop()
                speakingNow = false
                status("Stopped.")
                refreshButtons()
            }
            PhoneJob.CHECK -> {
                checkJob?.cancel()
                status("Stopped.")
                refreshButtons()
            }
            PhoneJob.NONE -> ensureMicThen { startVoice() }
        }
    }

    private fun ensureMicThen(block: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            block()
        } else {
            pendingTalkAfterPermission = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoice() {
        if (!gate.begin(PhoneJob.VOICE)) return
        refreshButtons()
        voiceJob = lifecycleScope.launch {
            var config: AgentConfig? = null
            try {
                config = effectiveConfig()
                refreshAgentCard(config)
                prepareConversation(config)
                if (!config.isConfigured) {
                    status(Tailnet.agentLabel(config))
                    return@launch
                }
                val voice = deps.voiceSettings(this@PhoneActivity)
                val out = ensureSpeech(config)
                val transcriber = deps.transcriber(voice, config)
                if (transcriber != null) {
                    controllerConversation(config, voice, transcriber, out)
                } else {
                    recognizerConversation(config, out)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.onAgentError(this@PhoneActivity, e, config?.profile ?: AgentProfile.GATEWAY)
                status("Error: ${shortError(e, config)}")
            } finally {
                controller = null
                gate.end(PhoneJob.VOICE)
                speakingNow = false
                refreshButtons()
            }
        }
    }

    /** Continuous hands-free conversation over the phone mic, like the truck's Talk screen. */
    private suspend fun controllerConversation(
        config: AgentConfig,
        voice: VoiceSettings,
        transcriber: Transcriber,
        out: SpeechOutput,
    ) {
        VoiceDiagnostics.event(this, "Phone Talk", "start; transcriber=${transcriber.javaClass.simpleName}")
        val agent = object : ConversationAgent {
            override suspend fun reply(userText: String): String = askAgent(config, userText)
        }
        val controllerConfig = deps.conversationConfig(voice).copy(
            continuous = true, // the screen keeps listening after each reply, no tap between turns
            speakErrors = false, // errors go to the status line where they can be read
        )
        val c = ConversationController(
            mic = deps.mic(this),
            transcriber = transcriber,
            agent = agent,
            speech = out,
            focus = deps.focus(this),
            config = controllerConfig,
            onState = { s -> runOnUiThread { onConversationState(s) } },
            onAgentError = { e ->
                // The controller handles this error itself (status line, no spoken errors);
                // route it to the same credential-refresh hook the typed path uses, so a
                // 401 from the agent rotates the vault key instead of being reused. The
                // turn still ends with a clear error — no automatic retry loop.
                deps.onAgentError(this@PhoneActivity, e, config.profile)
            },
            log = { line ->
                deps.log(line)
                VoiceDiagnostics.recorder(this, line)
            },
        )
        controller = c
        status("Listening…")
        c.run()
    }

    private fun onConversationState(s: ConversationState) {
        if (isFinishing || isDestroyed) return
        speakingNow = s is ConversationState.Speaking
        status(PhoneStatus.forState(s))
        refreshButtons()
    }

    /**
     * Fallback when no transcription endpoint is configured: the phone's system speech
     * recognizer, one utterance per turn, in the same continuous loop with the same stop
     * phrases. Speech still goes to the selected agent, never to a realtime voice model.
     */
    private suspend fun recognizerConversation(config: AgentConfig, out: SpeechOutput) {
        VoiceDiagnostics.event(this, "Phone Talk", "start; transcriber=system recognizer")
        var misses = 0
        while (true) {
            status("Listening… (phone speech recognizer)")
            val heard = deps.recognizer(this).trim()
            if (heard.isBlank()) {
                if (++misses >= 2) {
                    status("Didn't catch that. Tap Talk to try again.")
                    return
                }
                continue
            }
            if (ConversationController.isStopPhrase(heard)) {
                status("Ended — you said stop.")
                out.speak("Okay.")
                return
            }
            misses = 0
            status("Thinking…")
            val spoken = askAgent(config, heard)
            speakingNow = true
            refreshButtons()
            status("Speaking… tap Interrupt to jump in.")
            val result = out.speak(spoken)
            speakingNow = false
            refreshButtons()
            if (result is SpeakResult.Failed) {
                status("Speech failed: ${result.message}")
                return
            }
        }
    }

    /** Stop button: end the voice conversation and release the microphone and speaker.
     * The gate stays held until the job itself unwinds, so a new conversation cannot start
     * over a stopping one. */
    private fun stopVoice(reason: String) {
        controller?.stop()
        voiceJob?.cancel()
        speech?.stop()
        speakingNow = false
        status(reason)
        refreshButtons()
    }

    /** Interrupt button: barge in on the controller, or stop the speaker in the recognizer loop. */
    private fun interruptSpeech() {
        if (gate.active != PhoneJob.VOICE) return
        val c = controller
        if (c != null) c.onMicTapped() else speech?.stop()
    }

    // ---------------------------------------------------------------- text conversation

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!gate.begin(PhoneJob.TEXT)) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        input.setText("")
        refreshButtons()
        textJob = lifecycleScope.launch {
            var config: AgentConfig? = null
            try {
                config = effectiveConfig()
                refreshAgentCard(config)
                prepareConversation(config)
                if (!config.isConfigured) {
                    status(Tailnet.agentLabel(config))
                    return@launch
                }
                val out = ensureSpeech(config)
                status("Thinking…")
                val spoken = askAgent(config, text)
                speakingNow = true
                refreshButtons()
                status("Speaking…")
                val result = out.speak(spoken)
                speakingNow = false
                status(if (result is SpeakResult.Failed) "Speech failed: ${result.message}" else PhoneStatus.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.onAgentError(this@PhoneActivity, e, config?.profile ?: AgentProfile.GATEWAY)
                status("Error: ${shortError(e, config)}")
            } finally {
                speakingNow = false
                gate.end(PhoneJob.TEXT)
                refreshButtons()
            }
        }
    }

    // ---------------------------------------------------------------- connection check

    /**
     * Refreshes the vault, then sends the same "ready" probe Test connection uses, through
     * the same chat path Talk uses — so "Hermes answered" means the real bridge route works.
     * The probe carries no conversation identity; it is not part of any conversation.
     */
    private fun checkConnection() {
        if (!gate.begin(PhoneJob.CHECK)) return
        refreshButtons()
        checkJob = lifecycleScope.launch {
            var config: AgentConfig? = null
            try {
                status("Checking the agent connection…")
                config = effectiveConfig(forceRefresh = true)
                refreshAgentCard(config)
                if (!config.isConfigured) {
                    status(Tailnet.agentLabel(config))
                    return@launch
                }
                val reply = deps.chat(config, null, listOf(ChatMessage("user", "Reply with the single word: ready"))).take(80)
                status(if (config.usesBridge) "Hermes answered through the bridge: \"$reply\"" else "Agent answered: \"$reply\"")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deps.onAgentError(this@PhoneActivity, e, config?.profile ?: AgentProfile.GATEWAY)
                status("Connection failed: ${shortError(e, config)}")
                val c = config
                if (c != null && Tailnet.hint(c) != null) showHint(c)
            } finally {
                gate.end(PhoneJob.CHECK)
                refreshButtons()
            }
        }
    }

    // ---------------------------------------------------------------- Tailscale

    /** Opens the Tailscale app (launch intent, else the known activity) or explains why not. */
    private fun openTailscale() {
        val launch = runCatching { packageManager.getLaunchIntentForPackage(Tailnet.PACKAGE) }.getOrNull()
        if (launch != null && runCatching { startActivity(launch) }.isSuccess) return
        val explicit = Intent()
            .setComponent(ComponentName(Tailnet.PACKAGE, "${Tailnet.PACKAGE}.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(explicit) }.isSuccess) return
        status("Tailscale isn't installed on this phone. Install it, sign in, then tap Talk.")
    }

    // ---------------------------------------------------------------- UI helpers

    private fun stopEverything(reason: String) {
        stopVoice(reason)
        textJob?.cancel()
        checkJob?.cancel()
        speech?.stop()
        refreshButtons()
    }

    private fun shortError(e: Exception, config: AgentConfig? = null): String {
        val endpoint = config?.chatCompletionsUrl()
        return when (e) {
            is com.mdc159.sierra.agent.AgentException -> e.message ?: "Something went wrong."
            else -> Http.describe(e, endpoint)
        }
    }

    private fun refreshAgentCard(config: AgentConfig? = null) {
        val c = config ?: runCatching { deps.config(this) }.getOrNull() ?: AgentConfig()
        agentLine.text = Tailnet.agentLabel(c)
        val hint = Tailnet.hint(c)
        if (hint != null) showHint(c) else {
            hintLine.visibility = View.GONE
            hintLine.text = ""
        }
    }

    private fun showHint(config: AgentConfig) {
        hintLine.text = Tailnet.hint(config) ?: return
        hintLine.visibility = View.VISIBLE
    }

    private fun status(text: String) {
        if (this::statusView.isInitialized) statusView.text = text
    }

    private fun refreshButtons() {
        if (!this::talkButton.isInitialized) return
        val active = gate.active
        // Stop is always reachable while something runs; everything else waits for idle.
        talkButton.text = if (active == PhoneJob.NONE) "Talk" else "Stop"
        talkButton.isEnabled = true
        interruptButton.isEnabled = active == PhoneJob.VOICE && speakingNow
        checkButton.isEnabled = active == PhoneJob.NONE
        sendButton.isEnabled = active == PhoneJob.NONE
        input.isEnabled = active == PhoneJob.NONE
        // A spoken conversation must outlast the screen timeout.
        if (active == PhoneJob.NONE) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderTranscript() {
        if (!this::transcriptView.isInitialized) return
        transcriptView.text = transcript.render()
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    internal fun conversationIdentity(): String = conversation.id

    companion object {
        /** Refresh the vault before a turn when the last read is older than this. */
        const val REFRESH_STALE_MS = 5 * 60_000L
        const val REFRESH_TIMEOUT_MS = 8_000L
    }
}

/**
 * Production wiring for [PhoneActivity], injectable piece by piece in tests. Factories run
 * on the main thread unless documented otherwise; they must never log or display secrets.
 */
internal data class PhoneDeps(
    val signedIn: (Context) -> Boolean = { ctx ->
        runCatching { Studio54.source(ctx).isSignedIn }.getOrDefault(false)
    },
    val lastFetched: (Context) -> Long? = { ctx ->
        runCatching { Studio54.source(ctx).lastFetched }.getOrNull()
    },
    val refresh: suspend (Context) -> RefreshResult = { ctx -> Studio54.refreshNow(ctx) },
    val config: (Context) -> AgentConfig = { ctx -> Studio54.agentConfig(ctx, ConfigStore.load(ctx)) },
    // Signed in to Studio 54, the vault may hold the ElevenLabs key: resolve it so the
    // transcriber (and TalkVoice, which caches on these settings) follows key rotation.
    val voiceSettings: (Context) -> VoiceSettings = { Studio54.voiceSettings(it, VoiceSettingsStore.load(it)) },
    val conversationConfig: (VoiceSettings) -> ConversationConfig = { it.phoneConversationConfig() },
    val chat: suspend (AgentConfig, String?, List<ChatMessage>) -> String = { c, conversationId, messages ->
        AgentClient(c, conversationId = conversationId).chatCancellable(messages)
    },
    val transcriber: (VoiceSettings, AgentConfig) -> Transcriber? = { v, c -> VoiceStack.transcriber(v, c) },
    val speech: (Context, VoiceSettings, AgentConfig) -> SpeechOutput = { ctx, v, c -> VoiceStack.speechOutput(ctx, v, c) },
    val mic: (Context) -> com.mdc159.sierra.voice.stt.MicSource = { ctx ->
        Microphones.forTalk { Microphones.phone(ctx) }
    },
    val focus: (Context) -> com.mdc159.sierra.voice.tts.AudioFocusGate = { ctx -> AndroidAudioFocus(ctx) },
    val recognizer: suspend (Context) -> String = { PhoneRecognizer.recognize(it) },
    val history: (Context, String, String) -> Unit = { ctx, q, a -> runCatching { History.add(ctx, q, a) } },
    /** The credential-refresh hook: a 401 while the vault supplies the key rotates it. */
    val onAgentError: (Context, Throwable, AgentProfile) -> Unit = { ctx, e, profile ->
        Studio54.onAgentError(ctx, e, profile)
    },
    val log: (String) -> Unit = { MicLog.i(it) },
)
