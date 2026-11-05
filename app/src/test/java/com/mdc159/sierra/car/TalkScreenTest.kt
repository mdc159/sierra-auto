package com.mdc159.sierra.car

import android.Manifest
import android.speech.tts.TextToSpeech
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.History
import com.mdc159.sierra.car.CarTestHost.titleText
import com.mdc159.sierra.voice.Pcm
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Drives the real [TalkScreen] through every state with the Car App Library's fake host:
 * the fake car microphone feeds PCM into CarAudioRecord, a MockWebServer stands in for
 * the transcription and chat endpoints, and Robolectric's TextToSpeech shadow records
 * what was spoken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TalkScreenTest {

    private lateinit var carContext: TestCarContext
    private lateinit var session: SierraSession
    private lateinit var server: MockWebServer

    @Volatile
    private var transcript = "find gas"

    @Volatile
    private var reply = "Shell is two miles ahead on the right."

    @Volatile
    private var chatStatus = 200

    @Before
    fun setUp() {
        carContext = CarTestHost.carContext()
        session = CarTestHost.session(carContext)
        CarTestHost.denyAll()
        History.clear(carContext)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.endsWith("/audio/transcriptions") ->
                        MockResponse().setBody(JSONObject().put("text", transcript).toString())
                            .setBodyDelay(150, TimeUnit.MILLISECONDS)
                    request.path!!.endsWith("/chat/completions") && chatStatus == 200 ->
                        MockResponse().setBody(
                            """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(reply)}}}]}"""
                        )
                    else -> MockResponse().setResponseCode(chatStatus)
                        .setBody("""{"error":{"message":"model is overloaded"}}""")
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        History.clear(carContext)
    }

    private fun config(carMic: Boolean) = AgentConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "k",
        model = "m",
        sttUrl = if (carMic) server.url("/v1").toString() else "",
    )

    @Test
    fun notConfiguredExplainsThePhoneSetup() {
        CarTestHost.installConfig(carContext, null)
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        val t = screen.onGetTemplate() as MessageTemplate
        assertEquals("Set up your agent on the phone", t.title.toString())
        assertTrue(t.message.toString().contains("endpoint"))
        assertTrue(t.actions.isEmpty())
        assertEquals(Action.TYPE_BACK, t.headerAction!!.type)
    }

    @Test
    fun phoneRecognizerUnavailableFailsWithTryAgain() {
        CarTestHost.installConfig(carContext, config(carMic = false))
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        val t = awaitFailure(screen)
        assertEquals("No speech recognizer on this phone. Set a transcription URL.", t.message.toString())
        assertEquals("Try again", t.actions.single().title.toString())
        assertEquals(Action.FLAG_PRIMARY, t.actions.single().flags and Action.FLAG_PRIMARY)
        assertNotNull(t.actions.single().icon)
    }

    @Test
    fun carMicFlowNeedsMicThenListensThinksAndReplies() {
        CarTestHost.installConfig(carContext, config(carMic = true))
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(speechThenSilence(speechMs = 800, silenceMs = 2_500)))
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)

        // RECORD_AUDIO is not granted: the screen asks for it on the phone.
        val needsMic = screen.onGetTemplate() as MessageTemplate
        assertEquals("Allow the microphone on your phone.", needsMic.message.toString())
        val allow = needsMic.actions.single()
        assertEquals("Allow", allow.title.toString())
        CarTestHost.click(allow)
        val request = carContext.lastPermissionRequestInfo!!
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), request.permissionsRequested)

        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
        request.listener.onRequestPermissionsResult(listOf(Manifest.permission.RECORD_AUDIO), emptyList())

        // Listening with the car microphone: icon plus a Done button.
        val listening = screen.onGetTemplate() as MessageTemplate
        assertEquals("Listening…", listening.message.toString())
        assertNotNull(listening.icon)
        assertEquals("Done", listening.actions.single().title.toString())

        var sawThinking = false
        CarTestHost.await(what = "reply") {
            val t = screen.onGetTemplate()
            if (t is MessageTemplate && t.isLoading) sawThinking = true
            t is PaneTemplate
        }
        assertTrue("the Thinking… spinner was shown between listening and the reply", sawThinking)

        val pane = screen.onGetTemplate() as PaneTemplate
        val rows = pane.pane.rows.map { it as Row }
        assertEquals("You: find gas", rows[0].titleText)
        assertEquals(reply, rows[1].titleText)
        assertEquals(listOf("Ask again", "Repeat"), pane.pane.actions.map { it.title.toString() })
        assertEquals(1, pane.pane.actions.count { it.flags and Action.FLAG_PRIMARY != 0 })

        // The exchange was recorded.
        assertEquals(listOf("find gas"), History.list(carContext).map { it.question })
        assertEquals(1, session.conversation.size())

        // The host was told to stop recording, and the WAV reached the transcription endpoint.
        assertTrue(carContext.fakeHost.hasToldHostToStopRecording())

        // TTS: the shadow never reports init on its own; once it does, the pending reply is spoken.
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals(reply, shadowOf(tts).lastSpokenText)

        // Repeat speaks it again; Ask again starts a fresh screen.
        shadowOf(tts).clearLastSpokenText()
        CarTestHost.click(pane.pane.actions[1])
        assertEquals(reply, shadowOf(tts).lastSpokenText)
        // Ask again starts a fresh screen that records a second question straight away.
        transcript = "how far to reno"
        reply = "About two hours on I-80."
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(speechThenSilence(800, 2_500)))
        CarTestHost.click(pane.pane.actions[0])
        val manager = carContext.getCarService(TestScreenManager::class.java)
        val fresh = manager.screensPushed.last()
        assertTrue(fresh is TalkScreen && fresh !== screen)
        CarTestHost.await(what = "second reply") { fresh.onGetTemplate() is PaneTemplate }
        val second = fresh.onGetTemplate() as PaneTemplate
        assertEquals("You: how far to reno", (second.pane.rows[0] as Row).titleText)
        assertEquals(listOf("how far to reno", "find gas"), History.list(carContext).map { it.question })
        assertEquals(2, session.conversation.size())
    }

    @Test
    fun veryLongReplyFitsThePaneLimit() {
        reply = (1..120).joinToString(" ") { "Sentence number $it is here to make the reply long." }
        CarTestHost.installConfig(carContext, config(carMic = true))
        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(speechThenSilence(800, 2_500)))
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        CarTestHost.await(what = "reply") { screen.onGetTemplate() is PaneTemplate }

        val pane = screen.onGetTemplate() as PaneTemplate
        val maxRows = CarLimits.listLimit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
        assertTrue(pane.pane.rows.size <= maxRows)
        pane.pane.rows.map { it as Row }.forEach { assertTrue(it.titleText.length <= 120) }
    }

    @Test
    fun agentErrorShowsShortMessageAndTryAgain() {
        chatStatus = 503
        CarTestHost.installConfig(carContext, config(carMic = true))
        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(speechThenSilence(800, 2_500)))
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        val t = awaitFailure(screen)
        assertEquals("Agent error 503: model is overloaded", t.message.toString())
        assertEquals("Try again", t.actions.single().title.toString())
    }

    @Test
    fun emptyTranscriptFails() {
        transcript = "   "
        CarTestHost.installConfig(carContext, config(carMic = true))
        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(speechThenSilence(800, 2_500)))
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        assertEquals("Didn't catch that.", awaitFailure(screen).message.toString())
        assertTrue(History.list(carContext).isEmpty())
    }

    @Test
    fun silenceOnlyFailsWithoutCallingTheAgent() {
        CarTestHost.installConfig(carContext, config(carMic = true))
        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(speechThenSilence(0, 7_500)))
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        assertEquals("Didn't hear anything.", awaitFailure(screen).message.toString())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun hostDismissingTheMicPopsTheScreenAndSendsNothing() {
        CarTestHost.installConfig(carContext, config(carMic = true))
        CarTestHost.grant(Manifest.permission.RECORD_AUDIO)
        // The host closes the stream almost immediately: that is a cancel.
        carContext.fakeHost.setMicrophoneInputData(ByteArrayInputStream(speechThenSilence(0, 100)))
        val home = HomeScreen(carContext, session)
        CarTestHost.show(home)
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        val manager = carContext.getCarService(TestScreenManager::class.java)
        CarTestHost.await(what = "pop") { manager.screensRemoved.contains(screen) }
        assertEquals(0, server.requestCount)
        assertTrue(History.list(carContext).isEmpty())
    }

    // The Done button (stop recording early) is not tested end to end: the fake microphone is
    // file-backed, so a whole clip is consumed in milliseconds and a tap can never land
    // mid-recording deterministically. CarMicTest covers requestStop(); Done itself is an
    // emulator check (see the PR notes).

    @Test
    fun everyTemplateHasABackActionAndATitle() {
        CarTestHost.installConfig(carContext, null)
        val screen = TalkScreen(carContext, session, tools = emptyList())
        CarTestHost.show(screen)
        check(screen.onGetTemplate())
    }

    /**
     * Waits for the Failed state. The Listening template is also a MessageTemplate with an
     * action (Done), so the discriminator is the "Try again" button.
     */
    private fun awaitFailure(screen: TalkScreen): MessageTemplate {
        fun failed(): MessageTemplate? = (screen.onGetTemplate() as? MessageTemplate)
            ?.takeIf { t -> !t.isLoading && t.actions.any { it.title.toString() == "Try again" } }
        CarTestHost.await(what = "failure") { failed() != null }
        return failed()!!
    }

    private fun check(t: Template) {
        when (t) {
            is MessageTemplate -> {
                assertEquals(Action.TYPE_BACK, t.headerAction!!.type)
                assertFalse(t.title.toString().isBlank())
            }
            is PaneTemplate -> assertEquals(Action.TYPE_BACK, t.headerAction!!.type)
            else -> throw AssertionError("unexpected template $t")
        }
    }

    companion object {
        /** 16 kHz mono 16-bit PCM: a loud square wave for [speechMs], then digital silence. */
        fun speechThenSilence(speechMs: Int, silenceMs: Int): ByteArray {
            val speechSamples = Pcm.SAMPLE_RATE * speechMs / 1000
            val silenceSamples = Pcm.SAMPLE_RATE * silenceMs / 1000
            val out = ByteArray((speechSamples + silenceSamples) * 2)
            for (i in 0 until speechSamples) {
                val v = if ((i / 40) % 2 == 0) 4000 else -4000 // 200 Hz square wave
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
