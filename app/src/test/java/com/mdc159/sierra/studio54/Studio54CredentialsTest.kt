package com.mdc159.sierra.studio54

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Studio54CredentialsTest {

    private val extras = mapOf(
        "INFISICAL_API_URL" to "http://vault.tail1234.ts.net:8080/",
        "INFISICAL_CLIENT_ID" to "11111111-2222-3333-4444-555555555555",
        "INFISICAL_CLIENT_SECRET" to "super-secret-value",
        "INFISICAL_PROJECT_ID" to "proj-agent",
        "S54_KEYS_PROJECT_ID" to "proj-keys",
    )

    @Test
    fun importParsesTheDocumentedExtras() {
        val c = Studio54Credentials.fromExtras { extras[it] }
        assertNotNull(c)
        c!!
        assertEquals("http://vault.tail1234.ts.net:8080", c.apiUrl)
        assertEquals("vault.tail1234.ts.net", c.vaultHost)
        assertEquals("proj-agent", c.projectId)
        assertEquals("proj-keys", c.keysProjectId)
        assertTrue(c.isComplete)
    }

    @Test
    fun keysProjectIsOptionalAndBlankMeansNone() {
        val c = Studio54Credentials.fromExtras { if (it == "S54_KEYS_PROJECT_ID") "  " else extras[it] }
        assertNotNull(c)
        assertNull(c!!.keysProjectId)
    }

    @Test
    fun importWithAMissingRequiredValueIsRejected() {
        for (required in listOf("INFISICAL_API_URL", "INFISICAL_CLIENT_ID", "INFISICAL_CLIENT_SECRET", "INFISICAL_PROJECT_ID")) {
            assertNull("$required missing", Studio54Credentials.fromExtras { if (it == required) null else extras[it] })
            assertNull("$required blank", Studio54Credentials.fromExtras { if (it == required) " " else extras[it] })
        }
    }

    @Test
    fun aUrlWithoutAHostIsIncomplete() {
        assertNull(Studio54Credentials.fromExtras { if (it == "INFISICAL_API_URL") "vault" else extras[it] })
        assertFalse(Studio54Credentials("not a url", "a", "b", "c").isComplete)
        assertEquals("", Studio54Credentials.hostOf("::::"))
    }

    @Test
    fun toStringNeverContainsTheSecretOrClientId() {
        val c = Studio54Credentials.fromExtras { extras[it] }!!
        val text = c.toString()
        assertFalse(text.contains("super-secret-value"))
        assertFalse(text.contains("11111111"))
        assertTrue(text.contains("vault.tail1234.ts.net"))
        assertTrue(text.contains("proj-agent"))
    }

    @Test
    fun secretCacheRoundTripsThroughJsonAndRejectsGarbage() {
        val cache = SecretCache("proj", 12345L, mapOf("B" to "2", "A" to "1"))
        val back = SecretCache.fromJson(cache.toJson())
        assertNotNull(back)
        assertEquals("proj", back!!.projectId)
        assertEquals(12345L, back.fetchedAt)
        assertEquals(mapOf("A" to "1", "B" to "2"), back.values)
        assertEquals(listOf("A", "B"), back.values.keys.toList())
        assertNull(SecretCache.fromJson(null))
        assertNull(SecretCache.fromJson(""))
        assertNull(SecretCache.fromJson("{\"projectId\":\"p\"}"))
        assertNull(SecretCache.fromJson("not json"))
        assertEquals("SecretCache(project=proj, fetchedAt=12345, names=[A, B])", cache.toString())
        assertTrue(cache.isFresh(now = 12345L + 10, maxAgeMillis = 10))
        assertFalse(cache.isFresh(now = 12345L + 11, maxAgeMillis = 10))
    }
}
