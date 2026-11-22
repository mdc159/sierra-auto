package com.mdc159.sierra.agent

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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

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
}
