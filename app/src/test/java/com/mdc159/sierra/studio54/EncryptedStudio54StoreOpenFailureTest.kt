package com.mdc159.sierra.studio54

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
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
 * A failed open must never cost the saved sign-in: the store keeps the encrypted file,
 * serves an in-memory copy for this run and reports why, and the next start tries the file
 * again (the same contract `agent/ConfigStore` follows). The Android Keystore does not
 * exist on the JVM, so the real open fails here exactly as it does on a phone after a
 * keystore reset — the case that used to wipe the sign-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedStudio54StoreOpenFailureTest {

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
    }
    private fun credentials(projectId: String = "agent") = Studio54Credentials(
        apiUrl = "http://vault.tail1234.ts.net:8080",
        clientId = "client",
        clientSecret = "secret",
        projectId = projectId,
    )

    @Test
    fun openFailureServesThisRunInMemoryAndNeverDeletesTheFile() {
        val store = EncryptedStudio54Store(context) { throw KeyStoreException("AndroidKeyStore not found") }

        assertNull(store.loadCredentials())
        assertEquals("KeyStoreException: AndroidKeyStore not found", store.openError)
        assertTrue("the store deleted its file: ${context.deleted}", context.deleted.isEmpty())

        val saved = credentials()
        store.saveCredentials(saved)
        assertEquals(saved.clientId, store.loadCredentials()?.clientId)
        assertEquals(saved.projectId, store.loadCredentials()?.projectId)

        val cache = SecretCache("agent", 123L, mapOf("OPENAI_API_KEY" to "k"))
        store.saveCache(cache)
        assertEquals("k", store.loadCache()?.values?.get("OPENAI_API_KEY"))

        assertTrue("the store deleted its file: ${context.deleted}", context.deleted.isEmpty())

        // Sign out still clears what this run sees, without touching the kept file.
        store.clear()
        assertNull(store.loadCredentials())
        assertNull(store.loadCache())
        assertTrue("the store deleted its file: ${context.deleted}", context.deleted.isEmpty())
    }
}
