package com.mdc159.sierra.voice.duplex

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.fail
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Short timings so the timeout and reconnect tests finish quickly. */
val testTiming = WebSocketSessionBase.Timing(handshakeTimeoutMs = 1_500, maxReconnects = 2, reconnectDelayMs = 50)

val testClient: OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .connectTimeout(2, TimeUnit.SECONDS)
    .build()

fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

/** Collects session events and lets tests wait for one. */
class EventSink : DuplexListener {
    val events = CopyOnWriteArrayList<DuplexEvent>()
    val queue = LinkedBlockingQueue<DuplexEvent>()

    override fun onEvent(event: DuplexEvent) {
        events += event
        queue.put(event)
    }

    inline fun <reified T : DuplexEvent> await(timeoutMs: Long = 3_000, crossinline match: (T) -> Boolean = { true }): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val wait = deadline - System.currentTimeMillis()
            if (wait <= 0) fail("Timed out waiting for ${T::class.simpleName}; got $events")
            val e = queue.poll(wait, TimeUnit.MILLISECONDS) ?: continue
            if (e is T && match(e)) return e
        }
    }

    inline fun <reified T : DuplexEvent> all(): List<T> = events.filterIsInstance<T>()
}

/**
 * A websocket server for one protocol, built on MockWebServer. Every connection is
 * upgraded; messages from the client land in [received]; [send] talks to the newest socket.
 */
open class FakeWebSocketServer : WebSocketListener() {
    val server = MockWebServer()
    val received = LinkedBlockingQueue<JSONObject>()
    val connections = AtomicInteger()
    val closes = LinkedBlockingQueue<Pair<Int, String>>()

    @Volatile
    var socket: WebSocket? = null

    /** When set, connections beyond this many get an HTTP 503 instead of an upgrade. */
    @Volatile
    var maxConnections = Int.MAX_VALUE

    /** Non-websocket requests (the ElevenLabs signed-URL call) are answered by this. */
    var httpResponder: (RecordedRequest) -> MockResponse? = { null }

    private val opened = CountDownLatch(1)

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                httpResponder(request)?.let { return it }
                if (connections.get() >= maxConnections) return MockResponse().setResponseCode(503)
                return MockResponse().withWebSocketUpgrade(this@FakeWebSocketServer)
            }
        }
        server.start()
    }

    fun shutdown() {
        runCatching { server.shutdown() }
    }

    /** `http://host:port` without a path. */
    fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        connections.incrementAndGet()
        socket = webSocket
        opened.countDown()
        onClientConnected(webSocket)
    }

    open fun onClientConnected(webSocket: WebSocket) {}

    override fun onMessage(webSocket: WebSocket, text: String) {
        val json = JSONObject(text)
        received.put(json)
        onClientMessage(webSocket, json)
    }

    open fun onClientMessage(webSocket: WebSocket, json: JSONObject) {}

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        closes.put(code to reason)
        webSocket.close(code, reason)
    }

    fun send(json: JSONObject) {
        socket!!.send(json.toString())
    }

    fun send(json: String) {
        socket!!.send(json)
    }

    /** Waits for a client message satisfying [match]. Others are kept aside. */
    fun await(timeoutMs: Long = 3_000, match: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        val skipped = mutableListOf<JSONObject>()
        try {
            while (true) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) fail("Timed out waiting for a client message; saw ${received.toList() + skipped}")
                val m = received.poll(wait, TimeUnit.MILLISECONDS) ?: continue
                if (match(m)) return m
                skipped += m
            }
        } finally {
            skipped.forEach { received.put(it) }
        }
    }

    fun awaitType(type: String, timeoutMs: Long = 3_000): JSONObject = await(timeoutMs) { it.optString("type") == type }

    fun noMessage(type: String, withinMs: Long = 300) {
        val deadline = System.currentTimeMillis() + withinMs
        val kept = mutableListOf<JSONObject>()
        while (System.currentTimeMillis() < deadline) {
            val m = received.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (m.optString("type") == type) fail("Did not expect a $type message: $m")
            kept += m
        }
        kept.forEach { received.put(it) }
    }
}

/** Speaks the OpenAI Realtime GA protocol: session.created on connect, session.updated after session.update. */
class FakeOpenAiServer : FakeWebSocketServer() {
    @Volatile
    var sendSessionCreated = true

    @Volatile
    var confirmSessionUpdate = true

    /** When set, the first connection is dropped with this code right after the handshake. */
    @Volatile
    var dropFirstConnectionWithCode: Int? = null

    override fun onClientConnected(webSocket: WebSocket) {
        if (sendSessionCreated) {
            webSocket.send(JSONObject().put("type", "session.created").put("session", JSONObject().put("id", "sess_1")).toString())
        }
    }

    override fun onClientMessage(webSocket: WebSocket, json: JSONObject) {
        if (json.optString("type") == "session.update" && confirmSessionUpdate) {
            webSocket.send(JSONObject().put("type", "session.updated").put("session", json.getJSONObject("session")).toString())
            val drop = dropFirstConnectionWithCode
            if (drop != null && connections.get() == 1) {
                webSocket.close(drop, "going away")
            }
        }
    }

    fun responseCreated(id: String = "resp_1") = send(
        JSONObject().put("type", "response.created").put("response", JSONObject().put("id", id).put("status", "in_progress")),
    )

    fun audioDelta(pcm: ByteArray, itemId: String = "item_a", type: String = "response.output_audio.delta") = send(
        JSONObject()
            .put("type", type)
            .put("response_id", "resp_1")
            .put("item_id", itemId)
            .put("output_index", 0)
            .put("content_index", 0)
            .put("delta", b64(pcm)),
    )

    fun responseDone(status: String = "completed") = send(
        JSONObject().put("type", "response.done").put("response", JSONObject().put("id", "resp_1").put("status", status)),
    )
}

/** Speaks the ElevenLabs Agents protocol: metadata after conversation_initiation_client_data. */
class FakeElevenServer(
    private val outputFormat: String = "pcm_24000",
    private val inputFormat: String = "pcm_16000",
) : FakeWebSocketServer() {
    @Volatile
    var sendMetadata = true

    override fun onClientMessage(webSocket: WebSocket, json: JSONObject) {
        if (json.optString("type") == "conversation_initiation_client_data" && sendMetadata) {
            webSocket.send(
                JSONObject()
                    .put("type", "conversation_initiation_metadata")
                    .put(
                        "conversation_initiation_metadata_event",
                        JSONObject()
                            .put("conversation_id", "conv_123")
                            .put("agent_output_audio_format", outputFormat)
                            .put("user_input_audio_format", inputFormat),
                    )
                    .toString(),
            )
        }
    }

    fun audio(pcm: ByteArray, eventId: Int) = send(
        JSONObject().put("type", "audio").put("audio_event", JSONObject().put("audio_base_64", b64(pcm)).put("event_id", eventId)),
    )
}

/** A tool that answers instantly and remembers what it was asked. */
class RecordingBridge(private val answer: String = "Oil change is due at 90,000 miles.") : AgentBridge {
    val questions = LinkedBlockingQueue<String>()
    override fun ask(question: String): String {
        questions.put(question)
        return answer
    }
}
