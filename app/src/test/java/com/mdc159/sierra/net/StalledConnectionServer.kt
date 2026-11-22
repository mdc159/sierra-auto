package com.mdc159.sierra.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Test-only HTTP server that accepts connections, swallows everything the client sends and
 * never answers. A client whose call is cancelled gets its coroutine back at once, and its
 * `Call.cancel()` closes the socket, which is exactly what the cancellation regression tests
 * assert: [clientClosedWithin] observes the connection the way the server would.
 */
class StalledConnectionServer {

    private val server = ServerSocket()
    private val firstClientConnected = CountDownLatch(1)
    private val firstClientClosed = CountDownLatch(1)
    @Volatile
    private var client: Socket? = null

    /** Base URL for the client to call, for example `http://localhost:8080/`. */
    val url: String get() = "http://localhost:${server.localPort}/"

    fun start(): StalledConnectionServer {
        server.bind(InetSocketAddress("localhost", 0))
        thread(name = "stalled-connection-server", isDaemon = true) {
            runCatching {
                val socket = server.accept()
                client = socket
                firstClientConnected.countDown()
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

    /** True when the first client connected within [timeoutMillis]. */
    fun clientConnectedWithin(timeoutMillis: Long): Boolean =
        firstClientConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** True when the first client closed its connection within [timeoutMillis]. */
    fun clientClosedWithin(timeoutMillis: Long): Boolean =
        firstClientClosed.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun shutdown() {
        runCatching { server.close() }
        runCatching { client?.close() }
    }
}
