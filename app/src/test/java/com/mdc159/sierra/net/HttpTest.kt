package com.mdc159.sierra.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
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
    fun otherErrorsUseTheirMessageTrimmedToScreenSize() {
        assertEquals("Network error.", Http.describe(IOException()))
        assertEquals("Network error.", Http.describe(IOException("   ")))
        assertEquals("boom", Http.describe(IllegalStateException("boom")))
        val long = Http.describe(IOException("x".repeat(500)))
        assertEquals(120, long.length)
    }

    @Test
    fun userAgentIdentifiesTheAppWithoutSecrets() {
        assertTrue(Http.USER_AGENT.startsWith("SierraAuto/"))
        assertTrue(Http.USER_AGENT.contains("github.com/mdc159/sierra-auto"))
    }
}
