package com.mdc159.sierra.voice.duplex

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared plumbing for the two websocket protocols: connect with a handshake timeout,
 * dispatch JSON messages, run tools off the socket thread, and reconnect with backoff
 * when the connection drops. Subclasses speak the vendor protocol.
 */
abstract class WebSocketSessionBase(
    protected val client: OkHttpClient,
    private val timing: Timing = Timing(),
) : DuplexSession {

    /** Handshake and reconnect timing. Tests shrink these. */
    data class Timing(
        val handshakeTimeoutMs: Long = 10_000,
        val maxReconnects: Int = 3,
        val reconnectDelayMs: Long = 1_000,
    )

    protected lateinit var listener: DuplexListener
        private set

    @Volatile
    private var socket: WebSocket? = null

    /** Connection counter; callbacks from an older connection are ignored. */
    @Volatile
    private var generation = 0

    private val closed = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)

    /** The latch of the connection currently being established, if any. */
    @Volatile
    private var handshake: CountDownLatch? = null

    @Volatile
    private var handshakeFailure: String? = null

    private val reconnecting = AtomicBoolean(false)

    /** Tool invocations run here so a slow agent never blocks the socket reader. */
    protected val toolExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "duplex-tools").apply { isDaemon = true }
    }

    /** Builds the upgrade request. May block (for example to fetch a signed URL). */
    @Throws(DuplexException::class)
    protected abstract fun buildRequest(): Request

    /** The socket is open: send whatever starts the conversation. */
    protected abstract fun onSocketOpen(socket: WebSocket)

    /** One JSON text frame from the service. Call [handshakeComplete] when ready for audio. */
    protected abstract fun onJson(message: JSONObject)

    /**
     * The handshake timed out. Return true to carry on anyway (a gateway that forwards
     * audio but not the confirmation event), false to fail [start].
     */
    protected open fun onHandshakeTimeout(): Boolean = false

    /** The connection dropped and a new one is about to be made. Reset per-connection state. */
    protected open fun onBeforeReconnect() {}

    /** [close] was called. Abort whatever the session still has running elsewhere (tools). */
    protected open fun onClose() {}

    override fun start(listener: DuplexListener) {
        this.listener = listener
        connect()
        emit(DuplexEvent.Connected(provider))
    }

    @Throws(DuplexException::class)
    private fun connect() {
        if (closed.get()) throw DuplexException("Conversation stopped.")
        val latch = CountDownLatch(1)
        handshake = latch
        handshakeFailure = null
        val request = buildRequest()
        if (closed.get()) throw DuplexException("Conversation stopped.")
        val gen = ++generation
        val startedAt = System.currentTimeMillis()
        // Host and path only: the query can carry a signed token.
        DuplexLog.i("session connecting provider=$provider gen=$gen host=${request.url.host} path=${request.url.encodedPath} auth=${request.header("Authorization") != null}")
        val ws = client.newWebSocket(request, SocketListener(gen))
        if (closed.get()) { ws.cancel(); throw DuplexException("Conversation stopped.") }
        if (generation == gen && socket == null) socket = ws
        val inTime = latch.await(timing.handshakeTimeoutMs, TimeUnit.MILLISECONDS)
        if (closed.get()) { ws.cancel(); throw DuplexException("Conversation stopped.") }
        handshakeFailure?.let {
            ws.cancel()
            DuplexLog.w("session handshake failed gen=$gen after=${System.currentTimeMillis() - startedAt}ms: ${DuplexLog.redact(it)}")
            throw DuplexException(it)
        }
        if (!inTime) {
            if (onHandshakeTimeout()) {
                handshake = null
                DuplexLog.w("session handshake unconfirmed gen=$gen after=${timing.handshakeTimeoutMs}ms; continuing")
                return
            }
            ws.cancel()
            DuplexLog.w("session handshake timeout gen=$gen after=${timing.handshakeTimeoutMs}ms")
            throw DuplexException("The voice service did not answer in time.")
        }
        handshake = null
        DuplexLog.i("session configured gen=$gen after=${System.currentTimeMillis() - startedAt}ms")
    }

    /** Subclasses call this when the service confirms the session is ready. */
    protected fun handshakeComplete() {
        handshake?.countDown()
    }

    /** Subclasses call this when the service refuses the session during the handshake. */
    protected fun handshakeFailed(message: String) {
        handshakeFailure = message
        handshake?.countDown()
    }

    protected val isHandshaking: Boolean get() = handshake != null

    protected fun send(json: JSONObject): Boolean = socket?.send(json.toString()) ?: false

    protected fun emit(event: DuplexEvent) {
        if (ended.get() && event !is DuplexEvent.Ended) return
        try {
            listener.onEvent(event)
        } catch (_: Exception) {
            // A listener bug must not kill the socket reader.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        DuplexLog.i("session close: local close requested (handshaking=$isHandshaking socket=${socket != null})")
        handshake?.countDown()
        socket?.close(1000, "User ended conversation")
        val abandoned = toolExecutor.shutdownNow().size
        // Interrupting the tool thread does not unblock a socket read; the tool is told itself.
        runCatching { onClose() }
        DuplexLog.i("session close: socket closing, running tool cancelled (queued tools dropped=$abandoned)")
        end("Ended")
    }

    protected val isClosed: Boolean get() = closed.get()

    private fun end(reason: String) {
        if (ended.compareAndSet(false, true)) {
            DuplexLog.i("session ended reason=${DuplexLog.redact(reason)}")
            try {
                listener.onEvent(DuplexEvent.Ended(reason))
            } catch (_: Exception) {
            }
        }
    }

    /** Lost the connection while running: try again, then give up. */
    private fun connectionLost(reason: String) {
        if (closed.get() || ended.get()) return
        if (isHandshaking) {
            handshakeFailed(reason)
            return
        }
        if (!reconnecting.compareAndSet(false, true)) return
        Thread({
            try {
                for (attempt in 1..timing.maxReconnects) {
                    if (closed.get()) return@Thread
                    DuplexLog.w("session reconnect attempt=$attempt/${timing.maxReconnects} reason=${DuplexLog.redact(reason)}")
                    emit(DuplexEvent.Reconnecting(attempt, reason))
                    try {
                        Thread.sleep(timing.reconnectDelayMs * attempt)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (closed.get()) return@Thread
                    onBeforeReconnect()
                    try {
                        connect()
                        emit(DuplexEvent.Connected(provider))
                        return@Thread
                    } catch (e: DuplexException) {
                        DuplexLog.w("session reconnect attempt=$attempt failed: ${DuplexLog.redact(e.message)}")
                        emit(DuplexEvent.Error(e.message ?: "Reconnect failed.", fatal = false))
                    }
                }
                emit(DuplexEvent.Error("Lost the voice connection.", fatal = true))
                end("Connection lost")
            } finally {
                reconnecting.set(false)
            }
        }, "duplex-reconnect").apply { isDaemon = true }.start()
    }

    private inner class SocketListener(private val gen: Int) : WebSocketListener() {
        private val current: Boolean get() = gen == generation

        // Server event types for logcat: each change of type is logged, a run of the same
        // type (audio deltas) is logged once with its length. Socket reader thread only.
        private var lastType: String? = null
        private var repeats = 0

        private fun logType(type: String) {
            if (type == lastType) {
                repeats++
                return
            }
            flushRepeats()
            lastType = type
            DuplexLog.i("rx $type")
        }

        private fun flushRepeats() {
            if (repeats > 0) DuplexLog.i("rx $lastType x${repeats + 1} in a row")
            repeats = 0
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current || closed.get()) { webSocket.cancel(); return }
            DuplexLog.i("session socket open gen=$gen http=${response.code}")
            socket = webSocket
            onSocketOpen(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current || closed.get()) return
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                DuplexLog.w("rx unreadable text frame chars=${text.length}")
                emit(DuplexEvent.Error("Unreadable message from the voice service.", fatal = false))
                return
            }
            logType(json.optString("type").ifBlank { json.keys().asSequence().firstOrNull() ?: "?" })
            try {
                onJson(json)
            } catch (e: Exception) {
                DuplexLog.w("rx handler failed type=${json.optString("type", "?")} error=${e.javaClass.simpleName}")
                emit(DuplexEvent.Error("Could not handle a ${json.optString("type", "?")} message.", fatal = false))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Neither protocol sends binary frames to the client; ignore.
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            flushRepeats()
            DuplexLog.i("session socket closing gen=$gen code=$code reason=${DuplexLog.redact(reason)}")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            DuplexLog.i("session socket closed gen=$gen code=$code reason=${DuplexLog.redact(reason)} local=${closed.get()}")
            if (!current || closed.get()) return
            if (code == 1000) {
                end(reason.ifBlank { "The voice service ended the conversation." })
            } else {
                connectionLost("Connection closed ($code${if (reason.isBlank()) "" else ": $reason"})")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            flushRepeats()
            DuplexLog.w("session socket failure gen=$gen http=${response?.code ?: "none"} error=${t.javaClass.simpleName}: ${DuplexLog.redact(t.message)} local=${closed.get()}")
            if (!current || closed.get()) return
            val reason = when {
                response != null && response.code != 101 ->
                    "The voice service refused the connection (HTTP ${response.code})."
                else -> com.mdc159.sierra.net.Http.describe(t)
            }
            connectionLost(reason)
        }
    }
}
