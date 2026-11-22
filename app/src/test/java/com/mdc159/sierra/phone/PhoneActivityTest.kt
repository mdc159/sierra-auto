package com.mdc159.sierra.phone

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentProfile
import com.mdc159.sierra.agent.ChatMessage
import com.mdc159.sierra.voice.FakeMic
import com.mdc159.sierra.voice.FakeSpeechOutput
import com.mdc159.sierra.voice.FakeTranscriber
import com.mdc159.sierra.voice.convo.ConversationConfig
import com.mdc159.sierra.voice.convo.VoiceSettings
import com.mdc159.sierra.voice.stt.EndpointConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Behavior and lifecycle of the phone home screen, with every network and audio piece
 * faked through [PhoneDeps]. What is real: the activity, its manifest launcher role, the
 * [com.mdc159.sierra.voice.convo.ConversationController] voice loop and the gating.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneActivityTest {

    private lateinit var activityController: ActivityController<PhoneActivity>
    private lateinit var activity: PhoneActivity

    private val speech = FakeSpeechOutput()
    private val chatCalls = Collections.synchronizedList(mutableListOf<Pair<String?, List<ChatMessage>>>())
    private val history = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    /** What the agent-error hook saw: a 401 here means the vault-refresh path was reached. */
    private val agentErrors = Collections.synchronizedList(mutableListOf<Throwable>())

    /** Blocks the first chat call until released, to observe in-flight behavior. */
    private var chatLatch: CountDownLatch? = null

    private fun testDeps(
        config: AgentConfig = bridgeConfig(),
        mic: com.mdc159.sierra.voice.stt.MicSource = FakeMic(),
        transcriber: com.mdc159.sierra.voice.stt.Transcriber? = FakeTranscriber("hello hermes"),
        recognizer: suspend (android.content.Context) -> String = { "" },
    ) = PhoneDeps(
        signedIn = { false },
        lastFetched = { null },
        refresh = { com.mdc159.sierra.studio54.RefreshResult.NotSignedIn },
        config = { config },
        voiceSettings = { VoiceSettings() },
        conversationConfig = { fastConversation(it) },
        chat = { _, id, messages ->
            chatCalls += id to messages
            try {
                runInterruptible(Dispatchers.IO) { chatLatch?.await(10, TimeUnit.SECONDS) }
            } catch (e: InterruptedException) {
                throw CancellationException("cancelled", e)
            }
            "Hello from Hermes."
        },
        transcriber = { _, _ -> transcriber },
        speech = { _, _, _ -> speech },
        mic = { mic },
        recognizer = recognizer,
        focus = { com.mdc159.sierra.voice.FakeAudioFocus() },
        history = { _, q, a -> history += q to a },
        onAgentError = { _, e, _ -> agentErrors += e },
    )

    private fun fastConversation(base: VoiceSettings): ConversationConfig = base.conversationConfig().copy(
        silenceTimeoutMs = 300,
        endpoint = EndpointConfig(silenceMs = 200, noSpeechMs = 400, maxMs = 5_000, minSpeechMs = 50, calibrationMs = 100),
    )

    private fun bridgeConfig(): AgentConfig = AgentConfig(
        profile = AgentProfile.BRIDGE,
        bridgeUrl = "https://victoria.tail1234.ts.net:8443/v1",
        bridgeKey = "secret-bridge-token",
        bridgeModel = "hermes",
    )

    @Before
    fun setUp() {
        Shadows.shadowOf(application()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun application(): Application = RuntimeEnvironment.getApplication()

    private fun launch(deps: PhoneDeps): PhoneActivity {
        activityController = Robolectric.buildActivity(PhoneActivity::class.java)
        activity = activityController.get()
        activity.deps = deps
        activityController.setup()
        return activity
    }

    /** Runs the main looper while waiting for [condition]; fails the test on timeout. */
    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            assertTrue(
                "timed out; status='${activity.statusView.text}' transcript='${activity.transcriptView.text}'",
                System.currentTimeMillis() - start < timeoutMs,
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
    }

    /** Same, for a recreated activity instance the [activity] field no longer points at. */
    private fun waitUntil(recreated: PhoneActivity, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            assertTrue(
                "timed out; status='${recreated.statusView.text}' transcript='${recreated.transcriptView.text}'",
                System.currentTimeMillis() - start < timeoutMs,
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
    }

    // ------------------------------------------------------------------ launcher and screen

    @Test
    fun phoneActivityIsTheLauncherAndSettingsIsNot() {
        val context = application()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launch)
        assertEquals("com.mdc159.sierra.phone.PhoneActivity", launch!!.component!!.className)

        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0,
        )
        assertEquals("com.mdc159.sierra.phone.PhoneActivity", resolved?.activityInfo?.name)
    }

    @Test
    fun screenShowsSierraHeadingAgentTalkSettingsAndTypedInput() {
        launch(testDeps())
        val texts = allTexts(activity)
        assertTrue(texts.any { it == "Sierra" })
        assertTrue(activity.agentLine.text.contains("hermes"))
        assertTrue(activity.agentLine.text.contains("victoria.tail1234.ts.net"))
        assertFalse("credentials must never be shown", allTexts(activity).any { it.contains("secret-bridge-token") })
        assertTrue(activity.hintLine.visibility == android.view.View.VISIBLE) // tailnet host
        assertEquals("Talk", activity.talkButton.text.toString())
        assertEquals(PhoneStatus.IDLE, activity.statusView.text)
        assertTrue(activity.sendButton.isEnabled)
        assertTrue(activity.input.isEnabled)
        assertTrue(activity.checkButton.isEnabled)
        assertFalse(activity.interruptButton.isEnabled)
    }

    @Test
    fun settingsButtonOpensTheSettingsScreen() {
        launch(testDeps())
        activity.settingsButton.performClick()
        val next = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals("com.mdc159.sierra.MainActivity", next.component!!.className)
    }

    @Test
    fun unconfiguredAgentSaysSoInsteadOfTalking() {
        launch(testDeps(config = AgentConfig(profile = AgentProfile.BRIDGE)))
        activity.talkButton.performClick()
        waitUntil { activity.statusView.text.contains("Connect to Hermes") }
        assertTrue(activity.talkButton.isEnabled)
        assertEquals("Talk", activity.talkButton.text.toString())
        assertTrue(chatCalls.isEmpty())
    }

    // ------------------------------------------------------------------ text conversation

    @Test
    fun typedMessageGoesToTheAgentWithConversationIdentityAndIsSpoken() {
        launch(testDeps())
        activity.input.setText("hello hermes")
        activity.sendButton.performClick()
        waitUntil { activity.transcriptView.text.contains("Hello from Hermes.") }

        assertEquals(1, chatCalls.size)
        val (id, messages) = chatCalls.first()
        assertEquals(activity.conversationIdentity(), id)
        assertEquals("hello hermes", messages.last().content)
        assertEquals("system", messages.first().role)
        assertTrue(activity.transcriptView.text.contains("You: hello hermes"))
        assertTrue(activity.transcriptView.text.contains("Sierra: Hello from Hermes."))
        assertEquals("hello hermes" to "Hello from Hermes.", history.first())
        assertTrue(speech.spoken.contains("Hello from Hermes."))
        waitUntil { activity.sendButton.isEnabled }
        assertEquals(PhoneStatus.IDLE, activity.statusView.text)
    }

    @Test
    fun typedKeepsContextAndPreventsOverlappingJobs() {
        chatLatch = CountDownLatch(1)
        launch(testDeps())
        activity.input.setText("first")
        activity.sendButton.performClick()
        waitUntil { !activity.sendButton.isEnabled && activity.talkButton.text.toString() == "Stop" }

        activity.input.setText("second")
        activity.sendButton.performClick() // gate refuses the second text job

        chatLatch!!.countDown()
        waitUntil { chatCalls.size == 1 && activity.transcriptView.text.contains("Hello from Hermes.") }
        waitUntil { activity.sendButton.isEnabled && activity.talkButton.text.toString() == "Talk" }
        // The second text was not sent (still in the box).
        assertEquals("second", activity.input.text.toString())
    }

    @Test
    fun stopButtonCancelsAnInFlightTypedRequest() {
        chatLatch = CountDownLatch(1)
        launch(testDeps())
        activity.input.setText("long question")
        activity.sendButton.performClick()
        waitUntil { chatCalls.size == 1 }

        activity.talkButton.performClick() // reads Stop while a typed request is in flight

        waitUntil { activity.statusView.text.toString() == "Stopped." }
        waitUntil { activity.sendButton.isEnabled && activity.talkButton.text.toString() == "Talk" }
        assertTrue("the cancelled request must not reach the transcript", !activity.transcriptView.text.contains("Hello from Hermes."))
        chatLatch!!.countDown() // a late reply must still not appear
        Thread.sleep(100)
        assertFalse(activity.transcriptView.text.contains("Hello from Hermes."))
    }

    @Test
    fun textErrorShowsAMessageAndFreesTheScreen() {
        val deps = testDeps().let { base ->
            base.copy(chat = { _, id, messages ->
                chatCalls += id to messages
                throw com.mdc159.sierra.agent.AgentException("bridge down.")
            })
        }
        launch(deps)
        activity.input.setText("hello")
        activity.sendButton.performClick()
        waitUntil { activity.statusView.text.contains("bridge down.") }
        waitUntil { activity.sendButton.isEnabled && activity.talkButton.text.toString() == "Talk" }
        assertEquals(1, chatCalls.size)
    }

    // ------------------------------------------------------------------ voice conversation

    @Test
    fun productionPhoneConfigRecognizesQuietSpeechAndFollowupWithoutAnotherTap() {
        val mic = FakeMic()
        repeat(2) {
            mic.feedMs(160, 6)
            mic.feedMs(800, 150) // below the old car-derived 500 RMS threshold
            mic.feedMs(1_600, 6)
        }
        mic.feedMs(10_000, 6)
        val transcriber = FakeTranscriber("can you hear me", "what did I just ask")
        launch(testDeps(mic = mic, transcriber = transcriber).copy(
            conversationConfig = PhoneDeps().conversationConfig,
        ))

        activity.talkButton.performClick()
        waitUntil { activity.transcriptView.text.contains("You: what did I just ask") }
        waitUntil { activity.talkButton.text.toString() == "Talk" && activity.sendButton.isEnabled }
        assertEquals(2, transcriber.received.size)
        assertEquals(2, chatCalls.size)
        assertEquals(2, speech.spoken.size)
        assertEquals(chatCalls.first().first, chatCalls.last().first)
        assertTrue(chatCalls.last().second.any { it.content == "can you hear me" })
        assertTrue(activity.statusView.text.contains("Went quiet"))
    }

    @Test
    fun productionPhoneConfigSendsUnconfirmedFirstQuestionButNotQuietFollowup() {
        val mic = FakeMic()
        mic.feedMs(7_200, 20) // useful nonzero input below even the phone threshold
        mic.feedMs(9_000, 6)
        val transcriber = FakeTranscriber("a very quiet question")
        launch(testDeps(mic = mic, transcriber = transcriber).copy(
            conversationConfig = PhoneDeps().conversationConfig,
        ))

        activity.talkButton.performClick()
        waitUntil { activity.transcriptView.text.contains("You: a very quiet question") }
        waitUntil { activity.talkButton.text.toString() == "Talk" && activity.sendButton.isEnabled }
        assertEquals(1, transcriber.received.size)
        assertEquals(1, chatCalls.size)
        assertEquals(1, speech.spoken.size)
        assertTrue(activity.statusView.text.contains("Went quiet"))
    }

    @Test
    fun productionPhoneConfigDoesNotSendDigitalSilenceToTranscription() {
        val mic = FakeMic().also { it.feedMs(7_200, 0) }
        val transcriber = FakeTranscriber("must not be used")
        launch(testDeps(mic = mic, transcriber = transcriber).copy(
            conversationConfig = PhoneDeps().conversationConfig,
        ))

        activity.talkButton.performClick()
        waitUntil { activity.statusView.text.contains("only silence") }
        waitUntil { activity.talkButton.text.toString() == "Talk" && activity.sendButton.isEnabled }
        assertTrue(transcriber.received.isEmpty())
        assertTrue(chatCalls.isEmpty())
        assertTrue(speech.spoken.isEmpty())
    }

    @Test
    fun talkRunsAContinuousConversationAndEndsOnSilence() {
        val mic = FakeMic()
        // One spoken utterance, then enough silence that the follow-up listen ends the call.
        mic.feedMs(100, 100)
        mic.feedMs(400, 3_000)
        mic.feedMs(400, 100)
        mic.feedMs(1_200, 100)
        launch(testDeps(mic = mic))

        activity.talkButton.performClick()
        waitUntil { activity.talkButton.text.toString() == "Stop" }

        waitUntil { activity.transcriptView.text.contains("You: hello hermes") }
        waitUntil { activity.transcriptView.text.contains("Sierra: Hello from Hermes.") }
        assertTrue(speech.spoken.contains("Hello from Hermes."))
        assertEquals("hello hermes" to "Hello from Hermes.", history.first())
        assertEquals(activity.conversationIdentity(), chatCalls.first().first)

        waitUntil { activity.talkButton.text.toString() == "Talk" && activity.talkButton.isEnabled }
        assertTrue("silence should end the call with an invitation to talk again", activity.statusView.text.contains("Talk"))
        assertEquals(1, chatCalls.size)
    }

    @Test
    fun talkWithoutATranscriptionEndpointUsesThePhoneRecognizerLoop() {
        val heard = Collections.synchronizedList(mutableListOf<String>())
        launch(
            testDeps(
                transcriber = null,
                recognizer = {
                    val text = if (heard.isEmpty()) "hello there" else "stop"
                    heard += text
                    text
                },
            ),
        )
        activity.talkButton.performClick()
        waitUntil { activity.transcriptView.text.contains("Sierra: Hello from Hermes.") }
        waitUntil { activity.statusView.text.contains("you said stop") }
        assertTrue(speech.spoken.contains("Okay."))
        assertEquals(listOf("hello there", "stop"), heard)
        waitUntil { activity.talkButton.isEnabled }
    }

    @Test
    fun agent401DuringAControllerTurnTriggersExactlyOneVaultRefresh() {
        val mic = FakeMic()
        mic.feedMs(100, 100)
        mic.feedMs(400, 3_000)
        mic.feedMs(400, 100)
        mic.feedMs(1_200, 100)
        val deps = testDeps(mic = mic).copy(chat = { _, id, messages ->
            chatCalls += id to messages
            throw com.mdc159.sierra.agent.AgentException("Agent error 401: bad key")
        })
        launch(deps)
        activity.talkButton.performClick()

        waitUntil { activity.statusView.text.toString().contains("401") }
        waitUntil { activity.talkButton.text.toString() == "Talk" && activity.sendButton.isEnabled }

        assertEquals(
            "the controller turn must reach the same refresh hook as the typed path, exactly once",
            listOf("Agent error 401: bad key"),
            agentErrors.map { it.message },
        )
        assertEquals("no retry loop: one agent call for the failing turn", 1, chatCalls.size)
    }

    @Test
    fun talkWithoutMicPermissionDoesNotStartAConversation() {
        Shadows.shadowOf(application()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val mic = FakeMic()
        launch(testDeps(mic = mic))
        activity.talkButton.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Without the grant nothing starts: no job, no microphone open, no agent call.
        assertEquals(PhoneJob.NONE, gateOf(activity).active)
        assertEquals(0, mic.started)
        assertTrue(chatCalls.isEmpty())
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun onStopCancelsAnInFlightTurnStopsSpeechAndFreesTheScreen() {
        chatLatch = CountDownLatch(1)
        launch(testDeps())
        activity.input.setText("hello")
        activity.sendButton.performClick()
        waitUntil { chatCalls.size == 1 }

        activityController.stop() // the screen goes to the background

        waitUntil { activity.sendButton.isEnabled }
        assertTrue("onStop must stop the speaker", speech.stops >= 1)
        assertEquals(PhoneJob.NONE, gateOf(activity).active)
        chatLatch!!.countDown() // even if the interrupted call somehow finished, no second result shows
        waitUntil { activity.statusView.text.contains("Stopped") }
    }

    @Test
    fun recreationKeepsTheTranscriptAndConversationIdentity() {
        launch(testDeps())
        activity.input.setText("hello hermes")
        activity.sendButton.performClick()
        waitUntil { activity.transcriptView.text.contains("Hello from Hermes.") }
        val identityBefore = activity.conversationIdentity()

        activityController.recreate() // rotation/configuration change: same ViewModelStore
        val recreated = activityController.get()

        assertTrue(recreated.transcriptView.text.contains("You: hello hermes"))
        assertTrue(recreated.transcriptView.text.contains("Sierra: Hello from Hermes."))
        assertEquals("recreation must keep the bridge conversation identity", identityBefore, recreated.conversationIdentity())
    }

    @Test
    fun recreationDuringAnInFlightTurnDoesNotDuplicateOrCorruptState() {
        chatLatch = CountDownLatch(1)
        launch(testDeps())
        activity.input.setText("hello")
        activity.sendButton.performClick()
        waitUntil { chatCalls.size == 1 && activity.transcriptView.text.contains("You: hello") }

        activityController.recreate() // the turn is cancelled with the old activity
        chatLatch!!.countDown() // a late reply must not appear in the surviving transcript
        val recreated = activityController.get()

        waitUntil(recreated) { recreated.sendButton.isEnabled }
        val lines = recreated.transcriptView.text.lines().filter { it.isNotBlank() }
        assertEquals("one user line, no reply, no duplicates", listOf("You: hello"), lines)
    }

    @Test
    fun onDestroyReleasesTheSpeechEngine() {
        launch(testDeps())
        activity.input.setText("hello")
        activity.sendButton.performClick()
        waitUntil { activity.transcriptView.text.contains("Hello from Hermes.") } // speech is live
        activityController.destroy()
        assertTrue(speech.closed)
    }

    @Test
    fun openTailscaleWithoutTheAppSaysSo() {
        // Robolectric otherwise accepts even an unresolved explicit Intent.
        Shadows.shadowOf(application()).checkActivities(true)
        launch(testDeps())
        activity.tailscaleButton.performClick()
        waitUntil { activity.statusView.text.contains("Tailscale") }
        assertTrue(activity.statusView.text.contains("isn't installed"))
    }

    // ------------------------------------------------------------------ helpers

    private fun gateOf(activity: PhoneActivity): PhoneJobGate = activity.gate

    private fun allTexts(activity: PhoneActivity): List<String> {
        val out = mutableListOf<String>()
        fun walk(view: android.view.View?) {
            if (view == null) return
            if (view is android.widget.TextView) out += view.text.toString()
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.findViewById(android.R.id.content))
        return out
    }
}
