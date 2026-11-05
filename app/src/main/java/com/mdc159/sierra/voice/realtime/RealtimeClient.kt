package com.mdc159.sierra.voice.realtime

import com.mdc159.sierra.net.Http
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/** What a realtime session reports back. Callbacks arrive on OkHttp's websocket thread. */
interface RealtimeListener {
    /** The server accepted `session.update`; audio may now be sent. */
    fun onReady()

    /** A slice of the assistant's voice: 16-bit mono PCM at the wire sample rate. */
    fun onAudio(pcm: ByteArray)

    /** Running transcript of what the assistant is saying. */
    fun onAssistantText(delta: String)

    /** What the server heard the driver say, once a turn is transcribed. */
    fun onUserTranscript(text: String)

    /** Server-side voice activity detection heard the driver start talking (barge-in). */
    fun onSpeechStarted()

    /** The assistant finished one answer. */
    fun onResponseDone()

    fun onError(message: String)
    fun onClosed()
}

/** The sending half of a realtime session; [RealtimeClient] implements it, tests fake it. */
interface RealtimeChannel {
    /** Sends car-microphone PCM at the input sample rate; resampled to the wire rate. */
    fun sendMicAudio(pcm: ByteArray, length: Int = pcm.size)

    /** Asks the model to answer now (only needed without server VAD). */
    fun commitAndRespond()

    /** Stops the answer in progress (barge-in). */
    fun cancelResponse()

    fun close()
    val isOpen: Boolean
}

/**
 * EXPERIMENTAL. OpenAI Realtime protocol over a websocket to the gateway. The route
 * (`/v1/realtime?model=…`), the bearer header and the GA session shape were measured working
 * on the owner's gateway from a PC on 2026-11-04 (issue #39); nothing has run from the phone.
 * Tool calls and barge-in on this path are untested. The wire format and event handling are
 * unit-tested against a fake server. See docs/VOICE.md.
 */
class RealtimeClient(
    private val config: RealtimeConfig,
    private val listener: RealtimeListener,
    private val factory: WebSocket.Factory = Http.client,
) : RealtimeChannel {

    private var socket: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    override val isOpen: Boolean get() = open.get() && !closed.get()

    fun connect() {
        // Issue #39: the gateway accepted the bearer key with no other header; the beta header
        // is kept for servers that still want it and was harmless there.
        val builder = Request.Builder().url(config.websocketUrl())
            .header("OpenAI-Beta", "realtime=v1")
        if (config.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${config.apiKey}")
        socket = factory.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                webSocket.send(RealtimeEvents.sessionUpdate(config))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(RealtimeEvents.parse(text))

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(RealtimeEvents.parse(bytes.utf8()))

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed.compareAndSet(false, true)) listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed.compareAndSet(false, true)) {
                    val detail = response?.let { "Realtime connect failed: HTTP ${it.code}" } ?: Http.describe(t)
                    listener.onError(detail)
                    listener.onClosed()
                }
            }
        })
    }

    private fun handle(event: RealtimeEvents.Incoming) {
        when (event) {
            is RealtimeEvents.Incoming.SessionReady -> if (event.type == "session.updated" && ready.compareAndSet(false, true)) listener.onReady()
            is RealtimeEvents.Incoming.AudioDelta -> listener.onAudio(event.pcm)
            is RealtimeEvents.Incoming.AssistantTextDelta -> listener.onAssistantText(event.text)
            is RealtimeEvents.Incoming.UserTranscript -> listener.onUserTranscript(event.text)
            RealtimeEvents.Incoming.SpeechStarted -> listener.onSpeechStarted()
            RealtimeEvents.Incoming.ResponseDone -> listener.onResponseDone()
            is RealtimeEvents.Incoming.Error -> listener.onError(event.message)
            RealtimeEvents.Incoming.AudioDone, RealtimeEvents.Incoming.SpeechStopped, is RealtimeEvents.Incoming.Unknown -> Unit
        }
    }

    override fun sendMicAudio(pcm: ByteArray, length: Int) {
        val s = socket ?: return
        if (!isOpen || length <= 0) return
        val wire = Resampler.resample(pcm, length, config.inputSampleRate, config.wireSampleRate)
        s.send(RealtimeEvents.audioAppend(wire))
    }

    override fun commitAndRespond() {
        val s = socket ?: return
        s.send(RealtimeEvents.audioCommit())
        s.send(RealtimeEvents.responseCreate())
    }

    override fun cancelResponse() {
        socket?.send(RealtimeEvents.responseCancel())
    }

    override fun close() {
        val s = socket ?: return
        socket = null
        s.close(1000, "done")
    }
}
