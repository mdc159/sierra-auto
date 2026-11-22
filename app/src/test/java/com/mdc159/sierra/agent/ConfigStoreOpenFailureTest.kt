package com.mdc159.sierra.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import java.util.Collections

/**
 * A failed open must never cost the saved settings: the store keeps the encrypted file,
 * serves an in-memory copy for this run and reports why, and the next start tries the file
 * again (the same contract `voice/duplex/DuplexConfigStore` follows). The Android Keystore
 * does not exist on the JVM, so the real open fails here exactly as it does on a phone
 * after a keystore reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigStoreOpenFailureTest {

    /** Records deleteSharedPreferences so a test can prove the saved file is kept. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val deleted = Collections.synchronizedList(mutableListOf<String>())

        override fun getApplicationContext(): Context = this

        override fun deleteSharedPreferences(name: String): Boolean {
            deleted.add(name)
            return true
        }
    }

    private lateinit var context: RecordingContext

    @Before
    fun setUp() {
        context = RecordingContext(RuntimeEnvironment.getApplication())
        // The store is a singleton cached across tests; start every test from a clean one.
        ConfigStore.resetForTest { throw KeyStoreException("AndroidKeyStore not found") }
    }

    @Test
    fun openFailureServesDefaultsAndNeverDeletesTheFile() {
        val loaded = ConfigStore.load(context)

        assertEquals(AgentConfig(), loaded)
        assertEquals("KeyStoreException: AndroidKeyStore not found", ConfigStore.openError)
        assertTrue("the store deleted its file: ${context.deleted}", context.deleted.isEmpty())

        val saved = AgentConfig(
            baseUrl = "http://gateway.example.com/v1", apiKey = "k", model = "m",
            bridgeUrl = "http://bridge.example.com:8443/v1", bridgeKey = "bk", bridgeModel = "bm",
        )
        ConfigStore.save(context, saved)
        assertEquals(saved, ConfigStore.load(context))
        assertTrue("the store deleted its file: ${context.deleted}", context.deleted.isEmpty())

        ConfigStore.saveIdeaToken(context, "gh-token")
        assertEquals("gh-token", ConfigStore.loadIdeaToken(context))
    }

    @Test
    fun openErrorCarriesNoCredentialShapedValues() {
        ConfigStore.load(context)

        val why = ConfigStore.openError ?: ""
        assertTrue("the open error is empty", why.isNotBlank())
        assertTrue("the open error leaked a value: $why", !why.contains("sk-") && !why.contains("bearer "))
    }

    @Test
    fun aWorkingOpenReadsAndWritesTheFile() {
        val file: SharedPreferences = context.getSharedPreferences("sierra_test_open", Context.MODE_PRIVATE)
        ConfigStore.resetForTest { _ -> file }

        assertNull(ConfigStore.openError)
        ConfigStore.save(context, AgentConfig(baseUrl = "http://gw.example.com/v1", model = "m"))
        assertEquals("http://gw.example.com/v1", ConfigStore.load(context).baseUrl)
        assertNotNull(file.getString("base_url", null))
    }
}
