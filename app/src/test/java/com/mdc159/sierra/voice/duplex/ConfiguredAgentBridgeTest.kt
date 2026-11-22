package com.mdc159.sierra.voice.duplex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 7b991f2 (Duplex.kt): Stop must not leave an ask_agent exchange running. The bridge
 * keeps the exchange and a cancelled flag, so a cancel that lands before the request is
 * sent makes ask fail fast instead of reaching the owner's agent.
 *
 * The socket-close half of the cancellation (AgentClient.cancel on an in-flight call)
 * cannot be driven from the JVM host: ask() resolves its target through Studio54 and
 * ConfigStore, whose encrypted stores cannot be opened without an AndroidKeyStore. The
 * tool-side chain up to AgentBridge.cancel is pinned by
 * OpenAiRealtimeSessionBehaviorTest.stopCancelsARunningAskAgentCall.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfiguredAgentBridgeTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun cancelBeforeAskFailsFastWithoutReachingTheAgent() {
        val bridge = ConfiguredAgentBridge(context)
        bridge.cancel() // Stop landed before the model asked anything
        try {
            bridge.ask("What is on my calendar today?")
            fail("a cancelled bridge still reached the agent")
        } catch (e: IllegalStateException) {
            assertEquals("Conversation stopped.", e.message)
        }
    }

    @Test
    fun askAfterCancelStillFailsFastOnLaterCalls() {
        val bridge = ConfiguredAgentBridge(context)
        bridge.cancel()
        bridge.cancel() // Stop is idempotent
        repeat(3) {
            try {
                bridge.ask("again?")
                fail("ask after cancel must keep failing fast")
            } catch (e: IllegalStateException) {
                assertEquals("Conversation stopped.", e.message)
            }
        }
    }

    /** The cancelled flag is per bridge (per session), not a global switch. */
    @Test
    fun cancelDoesNotAffectANewSessionBridge() {
        ConfiguredAgentBridge(context).cancel()
        val fresh = ConfiguredAgentBridge(context)
        try {
            // Reaches the config stage, which fails on this host (no keystore) — the point
            // is that it does not fail fast with "Conversation stopped."
            fresh.ask("anything")
        } catch (e: IllegalStateException) {
            assertTrue("a fresh bridge was cancelled too: ${e.message}", e.message != "Conversation stopped.")
        } catch (e: Exception) {
            // Expected on a host without an AndroidKeyStore: the settings cannot be opened.
        }
    }
}
