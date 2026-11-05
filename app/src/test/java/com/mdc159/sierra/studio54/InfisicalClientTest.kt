package com.mdc159.sierra.studio54

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class InfisicalClientTest {

    private lateinit var server: MockWebServer
    private lateinit var vault: FakeInfisical
    private var now = 1_700_000_000_000L
    private val http = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        vault = FakeInfisical().apply {
            projects["agent"] = mapOf("OPENAI_BASE_URL" to "http://gw.ts.net:4000", "OPENAI_API_KEY" to "sk-agent")
        }
        server = MockWebServer().apply {
            dispatcher = vault
            start()
        }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() } // some tests shut it down themselves
    }

    private fun credentials(secret: String = vault.clientSecret) = Studio54Credentials(
        apiUrl = server.url("/").toString(),
        clientId = vault.clientId,
        clientSecret = secret,
        projectId = "agent",
    )

    private fun client(secret: String = vault.clientSecret) = InfisicalClient(credentials(secret), http) { now }

    @Test
    fun logsInWithUniversalAuthThenListsWithBearerToken() {
        val secrets = client().listSecrets("agent")
        assertEquals(mapOf("OPENAI_API_KEY" to "sk-agent", "OPENAI_BASE_URL" to "http://gw.ts.net:4000"), secrets)

        val login = vault.requests[0]
        assertEquals("POST", login.method)
        assertEquals(InfisicalClient.LOGIN_PATH, login.path)
        val body = JSONObject(login.body.readUtf8())
        assertEquals(vault.clientId, body.getString("clientId"))
        assertEquals(vault.clientSecret, body.getString("clientSecret"))

        val list = vault.requests[1]
        assertEquals("GET", list.method)
        assertEquals("Bearer token-1", list.getHeader("Authorization"))
        val url = list.requestUrl!!
        assertEquals(InfisicalClient.LIST_PATH, url.encodedPath)
        assertEquals("agent", url.queryParameter("workspaceId"))
        assertEquals("prod", url.queryParameter("environment"))
        assertEquals("/", url.queryParameter("secretPath"))
        assertEquals("true", url.queryParameter("viewSecretValue"))
        assertEquals("true", url.queryParameter("include_imports"))
    }

    @Test
    fun tokenIsReusedWhileFresh() {
        val c = client()
        c.listSecrets("agent")
        now += 60 * 60 * 1000 // one hour into a two-hour token
        c.listSecrets("agent")
        assertEquals(1, vault.loginCount)
        assertEquals(0, vault.renewCount)
        assertEquals(2, vault.listCount)
    }

    @Test
    fun renewsWhenTokenIsNearExpiry() {
        vault.ttlSeconds = 300
        val c = client()
        c.listSecrets("agent")
        now += 290 * 1000 // 10 s left, inside the renew margin
        c.listSecrets("agent")
        assertEquals(1, vault.loginCount)
        assertEquals(1, vault.renewCount)
        assertEquals(InfisicalClient.RENEW_PATH, vault.requests[2].path)
        assertEquals("token-1", JSONObject(vault.requests[2].body.readUtf8()).getString("accessToken"))
        assertEquals("Bearer token-1", vault.requests[3].getHeader("Authorization"))
    }

    @Test
    fun logsInAgainWhenRenewalIsRefused() {
        vault.ttlSeconds = 300
        val c = client()
        c.listSecrets("agent")
        vault.rejectRenew = true
        now += 295 * 1000
        c.listSecrets("agent")
        assertEquals(2, vault.loginCount)
        assertEquals("Bearer token-2", vault.requests.last().getHeader("Authorization"))
    }

    @Test
    fun logsInAgainInsteadOfRenewingPastMaxTtl() {
        vault.ttlSeconds = 300
        vault.maxTtlSeconds = 400
        val c = client()
        c.listSecrets("agent")
        now += 350 * 1000 // past the TTL, and the max TTL cannot cover another renewal
        c.listSecrets("agent")
        assertEquals(2, vault.loginCount)
        assertEquals(0, vault.renewCount)
    }

    @Test
    fun aServerSide401LeadsToOneFreshLoginAndRetry() {
        val c = client()
        c.listSecrets("agent")
        vault.revokeAllTokens()
        val secrets = c.listSecrets("agent")
        assertEquals("sk-agent", secrets["OPENAI_API_KEY"])
        assertEquals(2, vault.loginCount)
        assertEquals(3, vault.listCount) // first OK, one 401, retry OK
    }

    @Test
    fun wrongClientSecretIsReportedWithoutEchoingIt() {
        try {
            client(secret = "nope-very-secret").listSecrets("agent")
            fail("expected VaultException")
        } catch (e: VaultException) {
            assertEquals(401, e.code)
            assertFalse(e.unreachable)
            assertTrue(e.message!!.contains("Sign-in rejected"))
            assertFalse(e.message!!.contains("nope-very-secret"))
        }
    }

    @Test
    fun unknownProjectIs404() {
        try {
            client().listSecrets("missing")
            fail("expected VaultException")
        } catch (e: VaultException) {
            assertEquals(404, e.code)
            assertFalse(e.unreachable)
        }
    }

    @Test
    fun unreachableVaultIsFlaggedAsSuch() {
        val creds = credentials()
        server.shutdown()
        try {
            InfisicalClient(creds, http) { now }.listSecrets("agent")
            fail("expected VaultException")
        } catch (e: VaultException) {
            assertTrue(e.unreachable)
            assertNull(e.code)
            assertTrue(e.message!!.startsWith("Vault unreachable"))
        }
    }

    @Test
    fun ownSecretsWinOverImportsAndHiddenValuesAreSkipped() {
        vault.imports["agent"] = mapOf("OPENAI_API_KEY" to "sk-imported", "ELEVENLABS_API_KEY" to "el-imported")
        vault.hidden += "ELEVENLABS_API_KEY"
        val secrets = client().listSecrets("agent")
        assertEquals("sk-agent", secrets["OPENAI_API_KEY"])
        assertFalse(secrets.containsKey("ELEVENLABS_API_KEY"))
    }

    @Test
    fun malformedListIsReported() {
        val obj = JSONObject().put("nope", true)
        try {
            InfisicalClient.parseSecrets(obj)
            fail("expected JSONException")
        } catch (e: org.json.JSONException) {
            // expected: the client wraps this in a VaultException
        }
        assertEquals("", InfisicalClient.errorDetail("<html>"))
        assertEquals("Project not found", InfisicalClient.errorDetail("""{"statusCode":404,"message":"Project  not\nfound"}"""))
    }

    @Test
    fun invalidUrlIsRejectedUpFront() {
        try {
            InfisicalClient(Studio54Credentials("not a url", "a", "b", "c"), http)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Vault URL is not valid.", e.message)
        }
    }
}
