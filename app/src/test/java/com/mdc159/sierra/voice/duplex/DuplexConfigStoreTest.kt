package com.mdc159.sierra.voice.duplex

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 7b991f2: DuplexConfigStore no longer deletes its file when the encrypted store cannot
 * be opened (the old code called deleteSharedPreferences and retried, so one keystore
 * hiccup wiped the owner's saved settings). Instead it serves defaults from memory for
 * the run, records why, and leaves the file for the next start.
 *
 * The JVM host has no AndroidKeyStore, so the opener seam injects the failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DuplexConfigStoreTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val productionOpener = DuplexConfigStore.opener

    @Before
    fun setUp() {
        DuplexConfigStore.resetForTest()
    }

    @After
    fun tearDown() {
        DuplexConfigStore.opener = productionOpener
        DuplexConfigStore.resetForTest()
    }

    /** Reads the store file exactly as it lies on disk, bypassing the class under test. */
    private fun onDisk(): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Test
    fun openFailureKeepsTheSavedFileAndServesSettingsFromMemory() {
        onDisk().edit()
            .putString("instructions", "SAVED PROMPT")
            .putString("provider", "ELEVENLABS")
            .commit()
        var opens = 0
        DuplexConfigStore.opener = { c ->
            // Fails the way a keystore reset does; a later start would succeed again.
            if (opens++ == 0) throw IllegalStateException("keystore wiped") else c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }

        // This run: defaults from memory, the reason recorded, the file untouched.
        val loaded = DuplexConfigStore.load(context)
        assertEquals(DuplexConfig().provider, loaded.provider)
        assertEquals(DuplexConfig().instructions, loaded.instructions)
        val why = DuplexConfigStore.openError
        assertNotNull("openError was not set", why)
        assertTrue("openError names the cause: $why", why!!.contains("keystore wiped"))
        assertEquals("SAVED PROMPT", onDisk().getString("instructions", null))
        assertEquals("ELEVENLABS", onDisk().getString("provider", null))

        // Saves land in memory for this run, still not on disk.
        val edited = loaded.copy(instructions = "FOR THIS RUN", provider = DuplexProvider.ELEVENLABS)
        DuplexConfigStore.save(context, edited)
        assertEquals("FOR THIS RUN", DuplexConfigStore.load(context).instructions)
        assertEquals(DuplexProvider.ELEVENLABS, DuplexConfigStore.load(context).provider)
        assertEquals("SAVED PROMPT", onDisk().getString("instructions", null))
    }

    @Test
    fun aHealthyStoreRoundTripsTheSettings() {
        DuplexConfigStore.opener = { c -> c.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
        val saved = DuplexConfig(
            provider = DuplexProvider.ELEVENLABS,
            mode = DuplexMode.FULL_DUPLEX,
            openAiBaseUrl = "https://gw.example/v1",
            openAiApiKey = "sk-typed",
            openAiModel = "openai/gpt-realtime-2.1-mini",
            openAiVoice = "marin",
            openAiTranscriptionModel = "gpt-4o-mini-transcribe",
            elevenApiKey = "xi-typed",
            elevenAgentId = "agent_7",
            elevenVoiceId = "voice_9",
            instructions = "You are the bench Sierra.",
            guardRatio = 3.0,
        )
        DuplexConfigStore.save(context, saved)
        assertNull(DuplexConfigStore.openError)
        assertEquals(saved, DuplexConfigStore.load(context))
    }

    private companion object {
        /** The store's file name (DuplexConfigStore.FILE is private; the name is part of the contract). */
        const val FILE = "sierra_duplex_config"
    }
}
