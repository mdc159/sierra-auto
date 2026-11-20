package com.mdc159.sierra.agent.tools

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class FileIdeaToolTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun tool(token: String = "github_pat_test") =
        FileIdeaTool({ token }, http, repo = "mdc159/sierra-auto", apiBase = server.url("").toString().trimEnd('/'))

    @Test
    fun postsAnIdeaIssueAndReportsTheNumber() {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"number":42,"html_url":"https://github.com/mdc159/sierra-auto/issues/42"}"""),
        )
        val args = JSONObject().put("title", "Heated seat reminder").put("body", "Remind me to turn off the \"heated\" seats.")
        val out = JSONObject(tool().run(args))
        assertTrue(out.getBoolean("ok"))
        assertEquals(42, out.getInt("issue_number"))
        assertTrue(out.getString("note").contains("42"))
        assertFalse(out.toString().contains("github_pat_test"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/repos/mdc159/sierra-auto/issues", request.path)
        assertEquals("Bearer github_pat_test", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Heated seat reminder", body.getString("title"))
        assertTrue(body.getString("body").startsWith("Remind me to turn off the \"heated\" seats."))
        // Never `idea`: that label starts the cloud agent, and only the owner applies it by hand.
        assertEquals("idea-voice", body.getJSONArray("labels").getString(0))
        assertEquals(1, body.getJSONArray("labels").length())
        assertEquals("Filing your idea…", tool().status(args))
    }

    @Test
    fun missingTokenPointsToThePhone() {
        try {
            tool(token = " ").run(JSONObject().put("title", "t").put("body", "b"))
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("Idea settings"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun emptyIdeaIsRejectedBeforeAnyRequest() {
        try {
            tool().run(JSONObject())
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertEquals("Say what the idea is.", e.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun titleFallsBackToBodyAndIsBounded() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"number":7}"""))
        val longBody = "word ".repeat(50).trim()
        tool().run(JSONObject().put("body", longBody))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(FileIdeaTool.MAX_TITLE, body.getString("title").length)
        assertTrue(body.getString("body").startsWith(longBody))
    }

    @Test
    fun gitHubErrorsAreReadable() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
        try {
            tool().run(JSONObject().put("title", "t").put("body", "b"))
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("rejected the token"))
        }
        assertEquals("GitHub refused the issue: Validation Failed", FileIdeaTool.describeFailure(422, """{"message":"Validation Failed"}"""))
        assertTrue(FileIdeaTool.describeFailure(403, "").contains("Issues: read and write"))
        assertTrue(FileIdeaTool.describeFailure(404, "").contains("mdc159/sierra-auto"))
        assertEquals("GitHub error 500: boom", FileIdeaTool.describeFailure(500, "boom"))
    }

    @Test
    fun malformedSuccessBodyIsReported() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("<html>"))
        try {
            tool().run(JSONObject().put("title", "t").put("body", "b"))
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertEquals("GitHub sent a reply Sierra couldn't read.", e.message)
        }
    }

    @Test
    fun checkTokenReadsTheRepoWithoutCreatingAnything() {
        server.enqueue(MockResponse().setBody("""{"full_name":"mdc159/sierra-auto","permissions":{"push":true}}"""))
        val verdict = tool().checkToken()
        assertTrue(verdict.startsWith("Token OK"))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/mdc159/sierra-auto", request.path)

        server.enqueue(MockResponse().setBody("""{"full_name":"mdc159/sierra-auto","permissions":{"push":false}}"""))
        assertTrue(tool().checkToken().contains("cannot write"))

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        try {
            tool().checkToken()
            fail("expected ToolException")
        } catch (e: ToolException) {
            assertTrue(e.message!!.contains("Give the token access"))
        }
    }
}
