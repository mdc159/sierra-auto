package com.mdc159.sierra.voice.realtime

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Drives [RealtimeClient] against a fake realtime server on MockWebServer's websocket. */
class RealtimeClientTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    /** The fake server side: records what the client sends and lets the test push events. */
    private class FakeServer : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        lateinit var socket: WebSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason) // acknowledge the client's close handshake
        }

        fun send(json: String) = socket.send(json)
        fun next(): JSONObject = JSONObject(received.poll(5, TimeUnit.SECONDS) ?: error("server got nothing"))
    }

    private class RecordingListener : RealtimeListener {
        val audio = Collections.synchronizedList(mutableListOf<ByteArray>())
        val text = StringBuffer()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val done = CountDownLatch(1)

        override fun onReady() {
            events += "ready"
            ready.countDown()
        }

        override fun onAudio(pcm: ByteArray) {
            audio += pcm
        }

        override fun onAssistantText(delta: String) {
            text.append(delta)
        }

        override fun onUserTranscript(text: String) {
            events += "user:$text"
        }

        override fun onSpeechStarted() {
            events += "speech_started"
        }

        override fun onResponseDone() {
            events += "done"
            done.countDown()
        }

        override fun onError(message: String) {
            events += "error:$message"
        }

        override fun onClosed() {
            events += "closed"
            closed.countDown()
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun config() = RealtimeConfig(baseUrl = server.url("/v1").toString(), apiKey = "secret", model = "openai/gpt-realtime-2.1")

    @Test
    fun handshakeSendsAuthAndSessionUpdateThenStreamsAudioBothWays() {
        val fake = FakeServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val listener = RecordingListener()
        val client = RealtimeClient(config(), listener, http)
        client.connect()

        val request = server.takeRequest()
        assertEquals("/v1/realtime?model=openai%2Fgpt-realtime-2.1", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("realtime=v1", request.getHeader("OpenAI-Beta"))
        assertTrue(fake.opened.await(5, TimeUnit.SECONDS))

        val update = fake.next()
        assertEquals("session.update", update.getString("type"))
        assertEquals("realtime", update.getJSONObject("session").getString("type"))

        fake.send("""{"type":"session.created"}""")
        fake.send("""{"type":"session.updated"}""")
        assertTrue(listener.ready.await(5, TimeUnit.SECONDS))
        assertTrue(client.isOpen)

        // 16 kHz mic audio goes out resampled to 24 kHz.
        val mic = ByteArray(320) // 10 ms at 16 kHz
        client.sendMicAudio(mic, mic.size)
        val append = fake.next()
        assertEquals("input_audio_buffer.append", append.getString("type"))
        assertEquals(480, Base64.getDecoder().decode(append.getString("audio")).size) // 10 ms at 24 kHz

        // The model answers with audio, a transcript, and a done event.
        val pcm = byteArrayOf(1, 2, 3, 4)
        fake.send("""{"type":"input_audio_buffer.speech_started"}""")
        fake.send("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"hello"}""")
        fake.send("""{"type":"response.output_audio.delta","delta":"${Base64.getEncoder().encodeToString(pcm)}"}""")
        fake.send("""{"type":"response.output_audio_transcript.delta","delta":"Hi "}""")
        fake.send("""{"type":"response.output_audio_transcript.delta","delta":"there."}""")
        fake.send("""{"type":"response.done","response":{"status":"completed"}}""")
        assertTrue(listener.done.await(5, TimeUnit.SECONDS))
        assertArrayEquals(pcm, listener.audio.single())
        assertEquals("Hi there.", listener.text.toString())
        assertEquals(listOf("ready", "speech_started", "user:hello", "done"), listener.events)

        client.cancelResponse()
        assertEquals("response.cancel", fake.next().getString("type"))
        client.commitAndRespond()
        assertEquals("input_audio_buffer.commit", fake.next().getString("type"))
        assertEquals("response.create", fake.next().getString("type"))

        client.close()
        assertTrue(listener.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun serverErrorEventIsReportedAndRejectedUpgradeIsAFailure() {
        val fake = FakeServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(fake))
        val listener = RecordingListener()
        RealtimeClient(config(), listener, http).connect()
        assertTrue(fake.opened.await(5, TimeUnit.SECONDS))
        fake.next() // session.update
        fake.send("""{"type":"error","error":{"message":"Invalid model"}}""")
        fake.socket.close(1000, "bye")
        assertTrue(listener.closed.await(5, TimeUnit.SECONDS))
        assertTrue(listener.events.contains("error:Invalid model"))

        // A gateway without a realtime route answers the upgrade with plain HTTP.
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val second = RecordingListener()
        RealtimeClient(config(), second, http).connect()
        assertTrue(second.closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("error:Realtime connect failed: HTTP 404", "closed"), second.events)
    }
}
