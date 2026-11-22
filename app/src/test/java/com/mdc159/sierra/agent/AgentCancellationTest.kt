package com.mdc159.sierra.agent

import com.mdc159.sierra.agent.tools.Tool
import com.mdc159.sierra.agent.tools.ToolSchema
import com.mdc159.sierra.net.StalledBodyServer
import com.mdc159.sierra.net.StalledConnectionServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AgentCancellationTest {
    @Test fun cancellingPhoneRequestClosesAStalledBridgeCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val client = AgentClient(AgentConfig(profile = AgentProfile.BRIDGE,
                bridgeUrl = server.url("/v1").toString(), bridgeModel = "hermes", bridgeKey = "test"))
            val job = launch { client.chatCancellable(listOf(ChatMessage("user", "hello"))) }
            val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertNotNull(request)
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            server.shutdown()
        }
    }

    /**
     * The car Talk screen's chat path: cancelling the caller must abort the in-flight call
     * at once. A thread interrupt (runInterruptible's mechanism) cannot do that — the red
     * run of this test timed out waiting for the wrapper to unwind while the socket stayed
     * open until OkHttp's own timeouts.
     */
    @Test fun cancellingTheCarChatClosesAStalledCall() {
        val stalled = StalledConnectionServer().start()
        try {
            runBlocking {
                val client = AgentClient(AgentConfig(baseUrl = stalled.url, apiKey = "k", model = "m"))
                val job = CoroutineScope(Dispatchers.IO).launch {
                    client.chatCancellable(listOf(ChatMessage("user", "find gas")), emptyList(), null)
                }
                delay(300) // let the request reach the stalled server
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue("the stalled chat call's socket was not closed", stalled.clientClosedWithin(2_000))
            }
        } finally {
            stalled.shutdown()
        }
    }

    /** The same for the car transcription path, stalled mid-body so the read is the blocked part. */
    @Test fun cancellingTheCarTranscriptionClosesAStalledBodyRead() {
        val stalled = StalledBodyServer().start()
        try {
            runBlocking {
                val client = AgentClient(AgentConfig(baseUrl = stalled.url, sttUrl = stalled.url, apiKey = "k", model = "m"))
                val job = CoroutineScope(Dispatchers.IO).launch { client.transcribeCancellable(ByteArray(44)) }
                delay(300) // let the headers arrive so the body read is the blocked part
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue("the stalled transcription's socket was not closed", stalled.clientClosedWithin(2_000))
            }
        } finally {
            stalled.shutdown()
        }
    }

    /**
     * Stop/back during a tool must end the exchange at once: the tool's own 30 s timeout
     * must not decide when cancellation unwinds. The red run of this test (plain
     * withContext(IO) around a blocking Future.get) sat in cancelAndJoin until the tool
     * timed out, the tool thread was never interrupted, and the second tool call in the
     * same response still ran.
     */
    @Test fun cancellingDuringAToolInterruptsItAndSkipsTheRestOfThatResponse() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[
                     {"id":"a","type":"function","function":{"name":"blocker","arguments":"{}"}},
                     {"id":"b","type":"function","function":{"name":"second","arguments":"{}"}}]}}]}""",
            ),
        )
        server.start()
        try {
            val blocker = object : Tool {
                override val name = "blocker"
                override val description = "blocks until interrupted"
                override val parameters = ToolSchema.obj("query" to ToolSchema.string("q"), required = listOf("query"))
                override fun status(args: JSONObject) = "Running blocker…"
                val started = CountDownLatch(1)
                @Volatile var interrupted = false
                val exited = CountDownLatch(1)
                override fun run(args: JSONObject): String {
                    started.countDown()
                    return try {
                        Thread.sleep(60_000)
                        "{}"
                    } catch (e: InterruptedException) {
                        interrupted = true
                        Thread.currentThread().interrupt()
                        "{}"
                    } finally {
                        exited.countDown()
                    }
                }
            }
            val secondCalls = AtomicInteger(0)
            val second = object : Tool {
                override val name = "second"
                override val description = "must never run after Stop"
                override val parameters = ToolSchema.obj("query" to ToolSchema.string("q"), required = listOf("query"))
                override fun status(args: JSONObject) = "Running second…"
                override fun run(args: JSONObject): String {
                    secondCalls.incrementAndGet()
                    return "{}"
                }
            }
            val client = AgentClient(
                AgentConfig(baseUrl = server.url("/v1").toString(), apiKey = "k", model = "m"),
                OkHttpClient(),
                maxRounds = 4,
                roundTimeoutMs = 30_000,
                toolTimeoutMs = 30_000, // only Stop may end this tool, not its own timeout
            )
            runBlocking {
                val job = CoroutineScope(Dispatchers.IO).launch {
                    client.chatCancellable(listOf(ChatMessage("user", "go")), listOf(blocker, second), null)
                }
                assertTrue("the tool never started", blocker.started.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                // shutdownNow() interrupts the tool thread without waiting for it, so wait
                // (bounded) for the tool to leave before reading what it saw.
                assertTrue("the blocked tool never left", blocker.exited.await(2, TimeUnit.SECONDS))
                assertTrue("the blocked tool's thread was not interrupted", blocker.interrupted)
                assertEquals("a later tool in the same response ran after Stop", 0, secondCalls.get())
                assertEquals("another round was requested after Stop", 1, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }
}
