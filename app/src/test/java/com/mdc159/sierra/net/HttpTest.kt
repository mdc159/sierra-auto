package com.mdc159.sierra.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class HttpTest {

    @Test
    fun everyCallHasBoundedTime() {
        val c = Http.client
        assertTrue(c.connectTimeoutMillis in 1..15_000)
        assertTrue(c.readTimeoutMillis in 1..30_000)
        assertTrue(c.writeTimeoutMillis in 1..30_000)
        assertTrue(c.callTimeoutMillis in 1..60_000)
    }

    @Test
    fun timeoutsReadAsTimedOut() {
        assertEquals("Timed out. Check signal and try again.", Http.describe(SocketTimeoutException("timeout")))
        // OkHttp's call timeout surfaces as a plain InterruptedIOException("timeout").
        assertEquals("Timed out. Check signal and try again.", Http.describe(InterruptedIOException("timeout")))
    }

    @Test
    fun dnsAndTlsFailuresAreExplained() {
        assertEquals("No connection, or the server name is wrong.", Http.describe(UnknownHostException("api.example")))
        assertEquals("Secure connection failed.", Http.describe(SSLHandshakeException("bad cert")))
    }

    @Test
    fun refusedConnectionIsNotShownAsARawSocketAddress() {
        val e = ConnectException("Failed to connect to localhost/127.0.0.1:44555")
        assertEquals("Couldn't reach the server.", Http.describe(e))
    }

    @Test
    fun otherErrorsUseTheirMessageTrimmedToScreenSize() {
        assertEquals("Network error.", Http.describe(IOException()))
        assertEquals("Network error.", Http.describe(IOException("   ")))
        assertEquals("boom", Http.describe(IllegalStateException("boom")))
        val long = Http.describe(IOException("x".repeat(100) + "y".repeat(400)))
        assertEquals("x".repeat(100) + "y".repeat(20), long)
    }

    @Test
    fun userAgentIdentifiesTheAppWithoutSecrets() {
        assertEquals("SierraAuto/0.1 (personal Android Auto app; github.com/mdc159/sierra-auto)", Http.USER_AGENT)
    }

    @Test
    fun awaitDeliversTheResponse() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"text":" ok "}"""))
            Http.client.newCall(Request.Builder().url(server.url("/v1")).build()).await().use {
                assertEquals(200, it.code)
                assertEquals("""{"text":" ok "}""", it.body!!.string())
            }
            assertEquals("/v1", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun awaitSurfacesConnectionFailures() {
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        server.shutdown() // nothing is listening on that port any more
        try {
            runBlocking { Http.client.newCall(Request.Builder().url(url).build()).await() }
            fail("expected IOException")
        } catch (e: IOException) {
            // expected: the connection is refused
        }
    }

    /**
     * The whole point of the helper: cancelling the calling coroutine must cancel the call
     * itself, so the socket is released at once and the server sees the connection close.
     * Production timeouts are used, so a blocking implementation would stall ~20 s.
     */
    @Test
    fun cancellingTheCallerCancelsTheCallAndClosesTheConnection() {
        val stalled = StalledConnectionServer().start()
        try {
            val startedAt = System.nanoTime()
            runBlocking {
                val call = Http.client.newCall(Request.Builder().url(stalled.url).build())
                val job = launch { call.await() }
                delay(300) // let the request reach the stalled server
                job.cancel()
                job.join()
                assertTrue("the call was not cancelled", call.isCanceled())
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("await returned after ${elapsedMs}ms", elapsedMs < 2_500)
            assertTrue("the connection stayed open", stalled.clientClosedWithin(2_000))
        } finally {
            stalled.shutdown()
        }
    }
}
