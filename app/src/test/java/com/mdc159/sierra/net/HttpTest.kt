package com.mdc159.sierra.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun executeCancellableDeliversHeaderAndBody() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"text":" ok "}"""))
            val text = Http.client.newCall(Request.Builder().url(server.url("/v1")).build())
                .executeCancellable(Dispatchers.IO) { it.body!!.string() }
            assertEquals("""{"text":" ok "}""", text)
            assertEquals("/v1", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun executeCancellableSurfacesConnectionFailures() {
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        server.shutdown() // nothing is listening on that port any more
        try {
            runBlocking {
                Http.client.newCall(Request.Builder().url(url).build())
                    .executeCancellable(Dispatchers.IO) { it.body!!.string() }
            }
            fail("expected IOException")
        } catch (e: IOException) {
            // expected: the connection is refused
        }
    }

    /**
     * The body phase is the part `await()` alone cannot cancel: the server has already sent
     * the headers, so the only thing that can release the blocked body read is cancellation
     * still wired to `Call.cancel()` for the whole response lifetime.
     */
    @Test
    fun cancellingDuringTheBodyReadCancelsTheCallAndClosesTheConnection() {
        val stalled = StalledBodyServer().start()
        try {
            val startedAt = System.nanoTime()
            runBlocking {
                val call = Http.client.newCall(Request.Builder().url(stalled.url).build())
                val job = launch {
                    try {
                        call.executeCancellable(Dispatchers.IO) { it.body!!.string() }
                    } catch (e: CancellationException) {
                        // the expected outcome: the caller's own cancellation
                    }
                }
                delay(300) // let the headers arrive so the body read is the blocked part
                job.cancel()
                job.join()
                assertTrue("the call was not cancelled", call.isCanceled())
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("executeCancellable returned after ${elapsedMs}ms", elapsedMs < 2_500)
            assertTrue("the connection stayed open", stalled.clientClosedWithin(2_000))
        } finally {
            stalled.shutdown()
        }
    }

    /**
     * Cancellation must reach `Call.cancel()` even when the body read occupies the only
     * thread of its dispatcher (a single-thread or saturated pool, or the same dispatcher
     * as the caller). A guard dispatched on that pool would queue behind the blocked read.
     */
    @Test
    fun cancellingReachesTheCallWhenTheBodyReadHoldsTheOnlyThread() {
        val stalled = StalledBodyServer().start()
        val single = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val startedAt = System.nanoTime()
            runBlocking {
                val call = Http.client.newCall(Request.Builder().url(stalled.url).build())
                val readEntered = CountDownLatch(1)
                val job = launch(single) {
                    try {
                        call.executeCancellable(single) {
                            readEntered.countDown()
                            it.body!!.string()
                        }
                    } catch (e: CancellationException) {
                        // the expected outcome: the caller's own cancellation
                    }
                }
                assertTrue("body read never started", readEntered.await(5, TimeUnit.SECONDS))
                Thread.sleep(100) // the single thread is now blocked in the body read
                job.cancel() // from another thread
                withTimeout(5_000) { job.join() }
                assertTrue("the call was not cancelled", call.isCanceled())
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("executeCancellable returned after ${elapsedMs}ms", elapsedMs < 2_500)
            assertTrue("the connection stayed open", stalled.clientClosedWithin(2_000))
        } finally {
            single.close()
            stalled.shutdown()
        }
    }
}
