package com.mdc159.sierra.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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
}
