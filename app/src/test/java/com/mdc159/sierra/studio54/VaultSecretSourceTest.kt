package com.mdc159.sierra.studio54

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class VaultSecretSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var vault: FakeInfisical
    private lateinit var store: InMemoryStudio54Store
    private var now = 1_700_000_000_000L
    private val http = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        vault = FakeInfisical().apply {
            projects["agent"] = mapOf("OPENAI_BASE_URL" to "http://gw.ts.net:4000", "OPENAI_API_KEY" to "sk-agent")
            projects["keys"] = mapOf("OPENAI_API_KEY" to "sk-shared", "ELEVENLABS_API_KEY" to "el-shared")
        }
        server = MockWebServer().apply {
            dispatcher = vault
            start()
        }
        store = InMemoryStudio54Store()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() } // some tests shut it down themselves
    }

    private fun credentials(projectId: String = "agent", keysProjectId: String? = "keys") = Studio54Credentials(
        apiUrl = server.url("/").toString(),
        clientId = vault.clientId,
        clientSecret = vault.clientSecret,
        projectId = projectId,
        keysProjectId = keysProjectId,
    )

    private fun source(maxAge: Long = VaultSecretSource.DEFAULT_CACHE_MAX_AGE_MILLIS) = VaultSecretSource(
        store = store,
        clientFactory = { InfisicalClient(it, http) { now } },
        clock = { now },
        cacheMaxAgeMillis = maxAge,
    )

    @Test
    fun notSignedInHasNothing() {
        val s = source()
        assertFalse(s.isSignedIn)
        assertTrue(s.refresh() is RefreshResult.NotSignedIn)
        assertEquals(emptySet<String>(), s.names())
        assertNull(s.get(SecretNames.OPENAI_API_KEY))
        assertNull(s.lastFetched)
        assertFalse(s.status().signedIn)
    }

    @Test
    fun refreshReadsBothProjectsAndTheAgentProjectWins() {
        val s = source()
        s.signIn(credentials())
        val result = s.refresh()
        assertTrue(result is RefreshResult.Fetched)
        assertEquals(setOf("ELEVENLABS_API_KEY", "OPENAI_API_KEY", "OPENAI_BASE_URL"), s.names())
        assertEquals("sk-agent", s.get(SecretNames.OPENAI_API_KEY))
        assertEquals("el-shared", s.get(SecretNames.ELEVENLABS_API_KEY))
        assertEquals(now, s.lastFetched)
        assertEquals(1, vault.loginCount)
        assertEquals(2, vault.listCount)

        val status = s.status()
        assertTrue(status.signedIn)
        assertEquals("agent", status.projectId)
        assertEquals("keys", status.keysProjectId)
        assertFalse(status.servingFromCache)
        assertNull(status.lastError)

        val cached = store.loadCache()
        assertNotNull(cached)
        assertEquals("agent", cached!!.projectId)
        assertEquals("sk-agent", cached.values["OPENAI_API_KEY"])
    }

    @Test
    fun withoutAKeysProjectOnlyTheAgentProjectIsRead() {
        val s = source()
        s.signIn(credentials(keysProjectId = null))
        s.refresh()
        assertEquals(setOf("OPENAI_API_KEY", "OPENAI_BASE_URL"), s.names())
        assertEquals(1, vault.listCount)
    }

    @Test
    fun unreachableVaultServesTheWarmCache() {
        val s = source()
        s.signIn(credentials())
        s.refresh()
        val fetchedAt = s.lastFetched
        server.shutdown()

        now += 60 * 60 * 1000
        val result = s.refresh()
        assertTrue(result is RefreshResult.Failed)
        result as RefreshResult.Failed
        assertTrue(result.unreachable)
        assertTrue(result.cached)
        assertEquals("sk-agent", s.get(SecretNames.OPENAI_API_KEY))
        assertEquals(fetchedAt, s.lastFetched)
        val status = s.status()
        assertTrue(status.servingFromCache)
        assertTrue(status.lastError!!.startsWith("Vault unreachable"))
    }

    @Test
    fun cacheSurvivesAProcessRestartWithoutTheNetwork() {
        source().apply { signIn(credentials()); refresh() }
        server.shutdown()
        val restarted = source()
        assertTrue(restarted.isSignedIn)
        assertEquals("sk-agent", restarted.get(SecretNames.OPENAI_API_KEY))
        assertEquals(setOf("ELEVENLABS_API_KEY", "OPENAI_API_KEY", "OPENAI_BASE_URL"), restarted.names())
    }

    @Test
    fun aCacheOlderThanTheLimitIsNotServed() {
        val s = source(maxAge = 1000)
        s.signIn(credentials())
        s.refresh()
        server.shutdown()
        now += 1001
        assertNull(s.get(SecretNames.OPENAI_API_KEY))
        assertEquals(emptySet<String>(), s.names())
        assertNull(s.lastFetched)
        val result = s.refresh() as RefreshResult.Failed
        assertFalse(result.cached)
    }

    @Test
    fun signOutWipesSignInTokenAndCache() {
        val s = source()
        s.signIn(credentials())
        s.refresh()
        s.signOut()
        assertFalse(s.isSignedIn)
        assertNull(store.loadCredentials())
        assertNull(store.loadCache())
        assertEquals(emptySet<String>(), s.names())
        assertNull(s.get(SecretNames.OPENAI_API_KEY))
        assertNull(s.lastFetched)
        assertTrue(s.refresh() is RefreshResult.NotSignedIn)
        assertFalse(s.status().signedIn)
        // A fresh source over the same store sees nothing either.
        assertFalse(source().isSignedIn)
    }

    @Test
    fun signingInToAnotherProjectDropsTheOldCache() {
        vault.projects["other"] = mapOf("OPENAI_API_KEY" to "sk-other")
        val s = source()
        s.signIn(credentials())
        s.refresh()
        s.signIn(credentials(projectId = "other", keysProjectId = null))
        assertEquals(emptySet<String>(), s.names())
        s.refresh()
        assertEquals("sk-other", s.get(SecretNames.OPENAI_API_KEY))
    }

    @Test
    fun aWrongSecretIsReportedAndNothingIsCached() {
        val s = source()
        s.signIn(
            Studio54Credentials(server.url("/").toString(), vault.clientId, "wrong", "agent"),
        )
        val result = s.refresh() as RefreshResult.Failed
        assertFalse(result.unreachable)
        assertFalse(result.cached)
        assertTrue(result.message.contains("Sign-in rejected"))
        assertNull(store.loadCache())
        assertEquals(result.message, s.status().lastError)
    }

    @Test
    fun describeNeverMentionsAValue() {
        assertEquals("Fetched 2 secret name(s).", Studio54Activity.describe(RefreshResult.Fetched(setOf("A", "B"))))
        assertEquals(
            "Vault unreachable: x Serving the cached copy.",
            Studio54Activity.describe(RefreshResult.Failed("Vault unreachable: x", unreachable = true, cached = true)),
        )
        assertEquals(
            "Vault unreachable: x No cache to fall back on.",
            Studio54Activity.describe(RefreshResult.Failed("Vault unreachable: x", unreachable = true, cached = false)),
        )
        assertEquals("Not signed in.", Studio54Activity.describe(RefreshResult.NotSignedIn))
    }
}
