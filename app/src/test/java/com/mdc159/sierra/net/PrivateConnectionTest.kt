package com.mdc159.sierra.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class PrivateConnectionTest {
    @Test fun privateConnectionFailuresGiveActionWithoutExposingEndpoint() {
        listOf(UnknownHostException("private address"), ConnectException(), SocketTimeoutException()).forEach {
            val message = Http.describe(it, "https://agent.example.ts.net:8443/v1/chat/completions")
            assertTrue(message.contains("Tailscale"))
            assertFalse(message.contains("example.ts.net"))
            assertFalse(message.contains("8443"))
        }
    }

    @Test fun ordinaryInternetAndTlsFailuresKeepTheirOwnDiagnosis() {
        assertEquals("No connection, or the server name is wrong.", Http.describe(UnknownHostException(), "https://example.com/v1"))
        assertEquals("Secure connection failed.", Http.describe(SSLException("secret"), "https://agent.example.ts.net/v1"))
        assertEquals("Couldn't reach the server.", Http.describe(ConnectException(), "https://example.ts.net.attacker.test/v1"))
        assertEquals("Couldn't reach the server.", Http.describe(ConnectException(), "not a URL"))
    }

    @Test fun numericTailnetEndpointsAlsoExplainThePrivateRoute() {
        listOf("http://100.112.150.24:8787/v1", "http://[fd7a:115c:a1e0::1234]/v1").forEach {
            assertTrue(Http.describe(ConnectException(), it).contains("Tailscale"))
        }
        listOf("http://100.63.1.1", "http://100.128.1.1", "http://100.92.999.1").forEach {
            assertFalse(Http.describe(ConnectException(), it).contains("Tailscale"))
        }
    }
}
