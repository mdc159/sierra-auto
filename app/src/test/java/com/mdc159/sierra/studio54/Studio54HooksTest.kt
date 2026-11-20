package com.mdc159.sierra.studio54

import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.voice.duplex.DuplexConfig
import com.mdc159.sierra.voice.duplex.DuplexException
import com.mdc159.sierra.voice.duplex.DuplexProvider
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The car-side hooks (session start, Talk's settings, Talk's failure path) must survive an
 * encrypted store that cannot be opened. The JVM test host has no AndroidKeyStore, so it
 * fails the same way a phone does after a keystore reset or right after boot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Studio54HooksTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun assumeStoreCannotOpen() =
        assumeTrue("needs a host without AndroidKeyStore", runCatching { Studio54.source(context) }.isFailure)

    @Test
    fun agentConfigFallsBackToTheTypedSettingsWhenTheStoreCannotOpen() {
        assumeStoreCannotOpen()
        val typed = AgentConfig(baseUrl = "https://example.invalid/v1", apiKey = "typed-key", model = "m")
        assertEquals(typed, Studio54.agentConfig(context, typed))
    }

    @Test
    fun duplexConfigFallsBackToTheTypedSettingsWhenTheStoreCannotOpen() {
        assumeStoreCannotOpen()
        val typed = DuplexConfig(openAiApiKey = "typed-key")
        assertEquals(typed, Studio54.duplexConfig(context, typed))
    }

    @Test
    fun suppliesAgentIsFalseWhenTheStoreCannotOpen() {
        assumeStoreCannotOpen()
        assertEquals(false, Studio54.suppliesAgent(context))
    }

    @Test
    fun startAndTheFailureHookDoNotThrowWhenTheStoreCannotOpen() {
        assumeStoreCannotOpen()
        Studio54.start(context)
        Studio54.onAgentError(context, AgentException("Agent error 401: bad key"))
        Studio54.onAgentError(context, DuplexException("The voice service refused the connection (HTTP 401)."))
        Studio54.onDuplexError(context, DuplexProvider.OPENAI_REALTIME, "The voice service refused the connection (HTTP 401).")
        Studio54.onDuplexError(context, DuplexProvider.ELEVENLABS, null)
    }
}
