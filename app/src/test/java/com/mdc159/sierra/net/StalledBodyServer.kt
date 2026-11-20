package com.mdc159.sierra.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Test-only HTTP server for the mid-body stall regressions: it accepts one connection,
 * answers with a status line and headers whose Content-Length promises a much longer body
 * than it will ever send, writes the first bytes of that body and then goes silent.
 *
 * A client whose helper resumes on the response headers is now stuck in its blocking body
 * read, which is exactly the phase plain `Call.await()` cannot cancel. Only cancellation
 * that is wired to the call for the whole response lifetime releases it, and
 * [clientClosedWithin] observes that the way the server would: the client closed its side.
 */
class StalledBodyServer {

    private val server = ServerSocket()
    private val firstClientClosed = CountDownLatch(1)
    @Volatile
    private var client: Socket? = null

    /** Base URL for the client to call, for example `http://localhost:8080/`. */
    val url: String get() = "http://localhost:${server.localPort}/"

    fun start(): StalledBodyServer {
        server.bind(InetSocketAddress("localhost", 0))
        thread(name = "stalled-body-server", isDaemon = true) {
            runCatching {
                val socket = server.accept()
                client = socket
                val output = socket.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: $PROMISED_BODY_BYTES\r\n" +
                        "\r\n" +
                        "{\"text\":\"fi").toByteArray(Charsets.UTF_8),
                )
                output.flush()
                val input = socket.getInputStream()
                val buffer = ByteArray(8192)
                try {
                    // EOF (-1) means the client closed its side; Call.cancel() does exactly that.
                    while (input.read(buffer) != -1) { /* swallow the request */ }
                } catch (e: IOException) {
                    // A reset also proves the client went away.
                }
                firstClientClosed.countDown()
                runCatching { socket.close() }
            }
        }
        return this
    }

    /** True when the first client closed its connection within [timeoutMillis]. */
    fun clientClosedWithin(timeoutMillis: Long): Boolean =
        firstClientClosed.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun shutdown() {
        runCatching { server.close() }
        runCatching { client?.close() }
    }

    private companion object {
        /** Far more than the 13 body bytes the server actually writes. */
        const val PROMISED_BODY_BYTES = 8_192
    }
}
