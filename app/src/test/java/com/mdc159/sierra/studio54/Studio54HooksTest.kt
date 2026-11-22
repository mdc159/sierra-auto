package com.mdc159.sierra.studio54

import android.content.Context
import android.content.SharedPreferences
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.AgentException
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.voice.convo.VoiceSettings
import com.mdc159.sierra.voice.duplex.DuplexConfig
import com.mdc159.sierra.voice.duplex.DuplexException
import com.mdc159.sierra.voice.duplex.DuplexProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyStoreException

/**
 * The car-side hooks (session start, Talk's settings, Talk's failure path) must survive an
 * encrypted store that cannot be opened: with the store serving this run in memory, the
 * typed settings carry on, the vault supplies nothing and nothing throws. The open failure
 * is injected through the seams (an opener that throws, exactly what a phone sees after a
 * keystore reset), so the tests run everywhere instead of assuming the host cannot open
 * the store — the assumption the store's in-memory fallback silently turned into skips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Studio54HooksTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** The injected open failure: the Android Keystore does not exist. */
    private val failingOpener: (Context) -> SharedPreferences =
        { throw KeyStoreException("AndroidKeyStore not found") }

    @Before
    fun setUp() {
        Studio54.storeFactory = { EncryptedStudio54Store(it, failingOpener) }
        ConfigStore.resetForTest(failingOpener)
    }

    /** The hooks really saw a store that cannot open, not a vacuous pass. */
    private fun assertStoreFailedToOpen() {
        assertNotNull(
            "the injected open failure never reached the store",
            Studio54.source(context).status().storeError,
        )
    }

    @Test
    fun agentConfigFallsBackToTheTypedSettingsWhenTheStoreCannotOpen() {
        // Talk's exact flow: the typed settings come from ConfigStore, which cannot open either.
        val typed = ConfigStore.load(context)
        assertEquals(AgentConfig(), typed)
        assertEquals(typed, Studio54.agentConfig(context, typed))
        assertStoreFailedToOpen()
    }

    @Test
    fun duplexConfigFallsBackToTheTypedSettingsWhenTheStoreCannotOpen() {
        val typed = DuplexConfig(openAiApiKey = "typed-key")
        assertEquals(typed, Studio54.duplexConfig(context, typed))
        assertStoreFailedToOpen()
    }

    @Test
    fun voiceSettingsFallsBackToTheTypedSettingsWhenTheStoreCannotOpen() {
        val typed = VoiceSettings(elevenLabsKey = "typed-key")
        assertEquals(typed, Studio54.voiceSettings(context, typed))
        assertStoreFailedToOpen()
    }

    @Test
    fun suppliesAgentIsFalseWhenTheStoreCannotOpen() {
        assertEquals(false, Studio54.suppliesAgent(context))
        assertStoreFailedToOpen()
    }

    @Test
    fun startAndTheFailureHookDoNotThrowWhenTheStoreCannotOpen() {
        Studio54.start(context)
        Studio54.onAgentError(context, AgentException("Agent error 401: bad key"))
        Studio54.onAgentError(context, DuplexException("The voice service refused the connection (HTTP 401)."))
        Studio54.onDuplexError(context, DuplexProvider.OPENAI_REALTIME, "The voice service refused the connection (HTTP 401).")
        Studio54.onDuplexError(context, DuplexProvider.ELEVENLABS, null)
        assertStoreFailedToOpen()
    }
}
