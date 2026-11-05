package com.mdc159.sierra.agent

import com.mdc159.sierra.agent.tools.Tool
import com.mdc159.sierra.agent.tools.ToolException
import com.mdc159.sierra.agent.tools.ToolSchema
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/** The OpenAI `tools` / `tool_calls` round trip against a fake server. */
class ToolCallingTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun config() = AgentConfig(baseUrl = server.url("/v1").toString(), apiKey = "k", model = "m")

    private fun client(maxRounds: Int = 4, toolTimeoutMs: Long = 2_000) =
        AgentClient(config(), http, maxRounds = maxRounds, roundTimeoutMs = 3_000, toolTimeoutMs = toolTimeoutMs)

    /** A tool that records its calls and answers with canned JSON. */
    private class FakeTool(
        override val name: String,
        private val answer: (JSONObject) -> String,
    ) : Tool {
        val calls = mutableListOf<JSONObject>()
        override val description = "fake $name"
        override val parameters: JSONObject = ToolSchema.obj("query" to ToolSchema.string("q"), required = listOf("query"))
        override fun status(args: JSONObject) = "Running $name…"
        override fun run(args: JSONObject): String {
            calls += args
            return answer(args)
        }
    }

    private class RecordingListener : ToolListener {
        val events = mutableListOf<String>()
        override fun onToolStart(status: String) {
            events += "start:$status"
        }

        override fun onToolEnd() {
            events += "end"
        }
    }

    private fun toolCallReply(id: String, name: String, arguments: String) = MockResponse().setBody(
        """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${JSONObject.quote(arguments)}}}]}}]}""",
    )

    private fun textReply(text: String) =
        MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":${JSONObject.quote(text)}}}]}""")

    @Test
    fun runsToolAndFeedsResultBack() {
        server.enqueue(toolCallReply("call_1", "find_places", """{"query":"fuel"}"""))
        server.enqueue(textReply("Shell is half a mile ahead on the right."))
        val tool = FakeTool("find_places") { """{"results":[{"name":"Shell","distance_miles":0.5}]}""" }
        val listener = RecordingListener()

        val reply = client().chat(Conversation().messagesFor("Find gas"), listOf(tool), listener)

        assertEquals("Shell is half a mile ahead on the right.", reply)
        assertEquals("fuel", tool.calls.single().getString("query"))
        assertEquals(listOf("start:Running find_places…", "end"), listener.events)

        val first = JSONObject(server.takeRequest().body.readUtf8())
        val tools = first.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertEquals("find_places", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        assertTrue(tools.getJSONObject(0).getJSONObject("function").has("parameters"))

        val second = JSONObject(server.takeRequest().body.readUtf8())
        val messages = second.getJSONArray("messages")
        // system, user, assistant(tool_calls), tool
        assertEquals(4, messages.length())
        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertTrue(assistant.isNull("content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("find_places", call.getJSONObject("function").getString("name"))
        assertEquals("""{"query":"fuel"}""", call.getJSONObject("function").getString("arguments"))
        val toolMsg = messages.getJSONObject(3)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("call_1", toolMsg.getString("tool_call_id"))
        assertEquals("Shell", JSONObject(toolMsg.getString("content")).getJSONArray("results").getJSONObject(0).getString("name"))
        // The second round still offers tools (only the last round withholds them).
        assertTrue(second.has("tools"))
    }

    @Test
    fun lastRoundHasNoToolsSoTheDriverAlwaysGetsWords() {
        // Three rounds of tool calls, then the client must stop offering tools.
        repeat(3) { server.enqueue(toolCallReply("c$it", "loop", """{"query":"$it"}""")) }
        server.enqueue(textReply("Done."))
        val tool = FakeTool("loop") { """{"ok":true}""" }

        assertEquals("Done.", client(maxRounds = 4).chat(listOf(ChatMessage("user", "go")), listOf(tool), null))
        assertEquals(3, tool.calls.size)
        assertEquals(4, server.requestCount)
        repeat(3) { assertTrue(JSONObject(server.takeRequest().body.readUtf8()).has("tools")) }
        val last = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(last.has("tools"))
        // The last request carries the whole tool history: system? no, just user + 3 × (assistant, tool).
        assertEquals(1 + 3 * 2, last.getJSONArray("messages").length())
    }

    @Test
    fun endpointThatRejectsToolsFallsBackToPlainChat() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"Unrecognized request argument supplied: tools"}}"""),
        )
        server.enqueue(textReply("Plain answer."))
        val agent = client()
        val tool = FakeTool("find_places") { "{}" }

        assertEquals("Plain answer.", agent.chat(listOf(ChatMessage("user", "hi")), listOf(tool), null))
        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
        assertFalse(agent.supportsTools)

        // Tools stay off for this client from then on: no second rejection round trip.
        server.enqueue(textReply("Still plain."))
        assertEquals("Still plain.", agent.chat(listOf(ChatMessage("user", "again")), listOf(tool), null))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun otherServerErrorsAreNotRetriedWithoutTools() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            client().chat(listOf(ChatMessage("user", "hi")), listOf(FakeTool("t") { "{}" }), null)
            fail("expected AgentException")
        } catch (e: AgentException) {
            assertEquals("Agent error 401: bad key", e.message)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun toolFailuresBecomeErrorResultsNotCrashes() {
        server.enqueue(toolCallReply("a", "nope", "{}"))
        server.enqueue(toolCallReply("b", "boom", """{"query":"x"}"""))
        server.enqueue(textReply("Sorry, that didn't work."))
        val boom = FakeTool("boom") { throw ToolException("No location fix yet.") }

        val reply = client().chat(listOf(ChatMessage("user", "hi")), listOf(boom), null)
        assertEquals("Sorry, that didn't work.", reply)

        server.takeRequest()
        val afterUnknown = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val unknownResult = JSONObject(afterUnknown.getJSONObject(afterUnknown.length() - 1).getString("content"))
        assertTrue(unknownResult.getString("error").contains("Unknown tool nope"))
        val afterBoom = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val boomResult = JSONObject(afterBoom.getJSONObject(afterBoom.length() - 1).getString("content"))
        assertEquals("No location fix yet.", boomResult.getString("error"))
    }

    @Test
    fun slowToolTimesOutAndTheModelIsTold() {
        server.enqueue(toolCallReply("a", "slow", """{"query":"x"}"""))
        server.enqueue(textReply("That took too long."))
        val slow = FakeTool("slow") {
            Thread.sleep(5_000)
            "{}"
        }
        val started = System.nanoTime()
        val reply = client(toolTimeoutMs = 300).chat(listOf(ChatMessage("user", "hi")), listOf(slow), null)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("That took too long.", reply)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 4_000)
        server.takeRequest()
        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val result = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
        assertTrue(result.getString("error").contains("timed out"))
    }

    @Test
    fun badArgumentsAndObjectArgumentsAreHandled() {
        // Arguments as a JSON object (not a string) and as unparseable text.
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"",
                   "tool_calls":[{"type":"function","function":{"name":"t","arguments":{"query":"obj"}}},
                                 {"id":"x","type":"function","function":{"name":"t","arguments":"not json"}}]}}]}""",
            ),
        )
        server.enqueue(textReply("ok"))
        val tool = FakeTool("t") { """{"seen":"${it.optString("query")}"}""" }

        assertEquals("ok", client().chat(listOf(ChatMessage("user", "hi")), listOf(tool), null))
        assertEquals(1, tool.calls.size)
        assertEquals("obj", tool.calls[0].getString("query"))

        server.takeRequest()
        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val assistant = messages.getJSONObject(1)
        val calls = assistant.getJSONArray("tool_calls")
        assertEquals("call_0", calls.getJSONObject(0).getString("id")) // id filled in when the server omits it
        assertEquals("call_0", messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("x", messages.getJSONObject(3).getString("tool_call_id"))
        assertTrue(JSONObject(messages.getJSONObject(3).getString("content")).getString("error").contains("not a JSON object"))
    }

    @Test
    fun noToolsMeansTheOldPlainRequest() {
        server.enqueue(textReply("hi there"))
        assertEquals("hi there", client().chat(listOf(ChatMessage("user", "hi"))))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("tools"))
        assertEquals("hi", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun emptyFinalReplyIsAnError() {
        server.enqueue(toolCallReply("a", "t", "{}"))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":null}}]}"""))
        try {
            client().chat(listOf(ChatMessage("user", "hi")), listOf(FakeTool("t") { "{}" }), null)
            fail("expected AgentException")
        } catch (e: AgentException) {
            assertEquals("The agent sent an empty reply.", e.message)
        }
    }

    @Test
    fun eachRoundHasItsOwnTimeout() {
        // A server that never answers the second round: the exchange ends with a timeout
        // message instead of hanging.
        server.dispatcher = object : Dispatcher() {
            var n = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                n++
                return if (n == 1) {
                    toolCallReply("a", "t", "{}")
                } else {
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                }
            }
        }
        val agent = AgentClient(config(), http, roundTimeoutMs = 500)
        val started = System.nanoTime()
        try {
            agent.chat(listOf(ChatMessage("user", "hi")), listOf(FakeTool("t") { "{}" }), null)
            fail("expected AgentException")
        } catch (e: AgentException) {
            assertEquals("Timed out. Check signal and try again.", e.message)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_500)
    }

    @Test
    fun decodeHandlesNullContentAndMissingPieces() {
        val m = AgentClient.decode(JSONObject("""{"role":"assistant","content":null}"""))
        assertEquals("", m.content)
        assertTrue(m.toolCalls.isEmpty())
        val skipped = AgentClient.decode(
            JSONObject("""{"role":"assistant","content":"x","tool_calls":[{"id":"1","type":"function"},{"id":"2","type":"function","function":{"name":""}}]}"""),
        )
        assertTrue(skipped.toolCalls.isEmpty())
        assertNull(AgentClient.parseArguments("[1,2]"))
        assertEquals(0, AgentClient.parseArguments("")!!.length())
    }

    @Test
    fun encodeKeepsPlainMessagesUnchanged() {
        val plain = AgentClient.encode(ChatMessage("user", "hello"))
        assertEquals("""{"role":"user","content":"hello"}""", plain.toString())
        val withText = AgentClient.encode(ChatMessage("assistant", "Looking…", listOf(ToolCall("c", "t", "{}"))))
        assertEquals("Looking…", withText.getString("content"))
        assertEquals("{}", withText.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
    }
}
