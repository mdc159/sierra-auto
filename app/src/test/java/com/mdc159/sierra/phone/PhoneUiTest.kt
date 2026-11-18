package com.mdc159.sierra.phone

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentProfile
import com.mdc159.sierra.voice.convo.ConversationState
import com.mdc159.sierra.voice.convo.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the phone home screen: gating, context, transcript, status, hints. */
class PhoneUiTest {

    // ------------------------------------------------------------------ job gate

    @Test
    fun gateAllowsOnlyOneJobAtATime() {
        val gate = PhoneJobGate()
        assertTrue(gate.begin(PhoneJob.VOICE))
        assertFalse("text must not overlap the voice conversation", gate.begin(PhoneJob.TEXT))
        assertFalse("a second voice job must not start", gate.begin(PhoneJob.VOICE))
        assertEquals(PhoneJob.VOICE, gate.active)
        assertTrue(gate.end(PhoneJob.VOICE))
        assertEquals(PhoneJob.NONE, gate.active)
        assertTrue(gate.begin(PhoneJob.TEXT))
    }

    @Test
    fun gateEndsOnlyItsOwnJob() {
        val gate = PhoneJobGate()
        gate.begin(PhoneJob.TEXT)
        assertFalse("a stale stop must not free another job's slot", gate.end(PhoneJob.VOICE))
        assertEquals(PhoneJob.TEXT, gate.active)
        assertTrue(gate.end(PhoneJob.TEXT))
        assertFalse(gate.end(PhoneJob.TEXT)) // already idle
    }

    // ------------------------------------------------------------------ conversation context

    @Test
    fun conversationKeepsRollingContextAcrossTurns() {
        val c = PhoneConversation(maxTurns = 3)
        c.record("one", "1")
        c.record("two", "2")
        val messages = c.messagesFor("three")
        assertEquals("user", messages.last().role)
        assertEquals("three", messages.last().content)
        assertEquals("system", messages.first().role)
        // The earlier turns are still in context while the window holds.
        assertTrue(messages.any { it.role == "user" && it.content == "one" })
        assertEquals(2, c.size())
    }

    @Test
    fun conversationTrimsToTheWindow() {
        val c = PhoneConversation(maxTurns = 2)
        repeat(5) { c.record("q$it", "a$it") }
        val messages = c.messagesFor("next")
        assertTrue("the oldest turn must be dropped", messages.none { it.content == "q0" })
        assertTrue(messages.any { it.content == "q4" })
        assertEquals(2, c.size())
    }

    @Test
    fun clearRotatesTheBridgeConversationIdentity() {
        val c = PhoneConversation()
        val first = c.id
        c.record("hello", "hi")
        c.clear()
        assertNotEquals("a cleared context must not reuse the bridge session identity", first, c.id)
        assertEquals(0, c.size())
    }

    @Test
    fun systemPromptDoesNotAssumeDrivingAndAsksForSpokenForm() {
        val prompt = PhoneConversation.SYSTEM_PROMPT.lowercase()
        assertFalse("the phone is used off the truck too", prompt.contains("driving on the dashboard"))
        assertTrue(prompt.contains("phone"))
        assertTrue(prompt.contains("spoken"))
    }

    // ------------------------------------------------------------------ transcript

    @Test
    fun transcriptShowsUserAndAgentLinesAndCapsHistory() {
        val t = TranscriptLog(maxLines = 4)
        t.user("find fuel")
        t.agent("The nearest station is two miles away.")
        t.event("Switched to hermes on the Sierra bridge")
        t.user("thanks")
        t.agent("Any time.")
        val lines = t.render().lines()
        assertEquals(4, lines.size.toLong())
        assertEquals("You: thanks", lines[2])
        assertEquals("Sierra: Any time.", lines[3])
        assertFalse("the capped line must be gone", t.render().contains("find fuel"))
    }

    // ------------------------------------------------------------------ status text

    @Test
    fun statusFollowsTheConversationStates() {
        assertEquals("Listening…", PhoneStatus.forState(ConversationState.Listening(1)))
        assertEquals("Thinking…", PhoneStatus.forState(ConversationState.Thinking(2, "hello")))
        assertEquals("Transcribing…", PhoneStatus.forState(ConversationState.Thinking(2, null)))
        assertTrue(PhoneStatus.forState(ConversationState.Speaking(2, "hello", "hi")).startsWith("Speaking…"))
    }

    @Test
    fun statusExplainsEveryEndReason() {
        assertEquals("Ended — you said stop.", PhoneStatus.forEnd(EndReason.STOP_PHRASE, null))
        assertTrue(PhoneStatus.forEnd(EndReason.SILENCE, null).contains("Talk"))
        assertEquals("Stopped.", PhoneStatus.forEnd(EndReason.USER_STOPPED, null))
        assertEquals("Error: bridge down.", PhoneStatus.forEnd(EndReason.ERROR, "bridge down."))
    }

    // ------------------------------------------------------------------ Tailscale hints and labels

    @Test
    fun tailnetAddressesAreRecognized() {
        assertTrue(Tailnet.looksPrivate("https://victoria.tail1234.ts.net:8443/v1"))
        assertTrue(Tailnet.looksPrivate("https://100.101.102.103:8443/v1"))
        assertTrue(Tailnet.looksPrivate("http://192.168.1.10:8080/v1"))
        assertTrue(Tailnet.looksPrivate("http://10.0.0.5/v1"))
        assertTrue(Tailnet.looksPrivate("http://172.20.0.4/v1"))
        assertFalse(Tailnet.looksPrivate("https://api.openai.com/v1"))
        assertFalse("100.x outside CGNAT is a normal public address", Tailnet.looksPrivate("https://100.5.5.5/v1"))
        assertFalse(Tailnet.looksPrivate("not a url"))
    }

    @Test
    fun bridgeOnAPrivateHostGetsTheTailscaleHint() {
        val config = AgentConfig(
            profile = AgentProfile.BRIDGE,
            bridgeUrl = "https://victoria.tail1234.ts.net:8443/v1",
            bridgeKey = "secret-token",
            bridgeModel = "hermes",
        )
        val hint = Tailnet.hint(config)
        assertTrue(hint!!.contains("Tailscale"))
        // The gateway profile never gets the hint, even at a private address.
        assertNull(Tailnet.hint(config.copy(profile = AgentProfile.GATEWAY)))
    }

    @Test
    fun agentLabelNamesTheModelAndHostButNeverCredentials() {
        val bridge = AgentConfig(
            profile = AgentProfile.BRIDGE,
            bridgeUrl = "https://victoria.tail1234.ts.net:8443/v1",
            bridgeKey = "super-secret-bridge-token",
            bridgeModel = "hermes",
        )
        val label = Tailnet.agentLabel(bridge)
        assertTrue(label.contains("hermes"))
        assertTrue(label.contains("victoria.tail1234.ts.net"))
        assertFalse("the bridge token must never be displayed", label.contains("super-secret-bridge-token"))
        assertTrue(Tailnet.agentLabel(AgentConfig()).contains("No agent configured"))
        assertTrue(
            Tailnet.agentLabel(AgentConfig(profile = AgentProfile.BRIDGE)).contains("Connect to Hermes"),
        )
    }
    @Test
    fun malformedEndpointNeverAppearsInAgentLabel() {
        for (url in listOf(
            "https://user:fixture-password@bad_host/v1?token=fixture-token",
            "invalid endpoint?token=fixture-token",
        )) {
            val config = AgentConfig(
                profile = AgentProfile.BRIDGE,
                bridgeUrl = url,
                bridgeKey = "fixture-key",
                bridgeModel = "hermes",
            )
            for (candidate in listOf(config, config.copy(
                profile = AgentProfile.GATEWAY,
                baseUrl = url,
                apiKey = "fixture-key",
                model = "fixture-model",
            ))) {
                val label = Tailnet.agentLabel(candidate)
                assertTrue(label.contains("invalid endpoint"))
                assertFalse(label.contains("fixture-password"))
                assertFalse(label.contains("fixture-token"))
                assertFalse(label.contains("bad_host"))
            }
        }
    }
}
