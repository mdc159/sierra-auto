package com.mdc159.sierra.voice.realtime

import com.mdc159.sierra.voice.stt.MicSource
import com.mdc159.sierra.voice.tts.AudioFocusGate
import com.mdc159.sierra.voice.tts.PcmPlayer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EXPERIMENTAL. Pumps the car microphone into a realtime session and the session's voice
 * into a [PcmPlayer], full duplex. Server-side voice detection handles turn taking; when
 * the server hears the driver start talking, queued assistant audio is dropped (barge-in).
 *
 * Holds exclusive transient focus for the whole session, as the car-mic guidance requires.
 * Runs blocking on the calling thread until [stop], the host ends the mic, or the socket
 * closes; call it from a background coroutine.
 */
class RealtimeVoiceLoop(
    private val mic: MicSource,
    private val playerFactory: () -> PcmPlayer,
    private val focus: AudioFocusGate,
    private val connect: (RealtimeListener) -> RealtimeChannel,
    private val wireSampleRate: Int = 24_000,
    private val readyTimeoutMs: Long = 8_000,
    private val onEvent: (Event) -> Unit = {},
) {
    sealed class Event {
        data object Ready : Event()
        data class UserSaid(val text: String) : Event()
        data class AssistantText(val delta: String) : Event()
        data object ResponseDone : Event()
        data class Ended(val reason: String) : Event()
    }

    private val stopped = AtomicBoolean(false)
    private val ready = java.util.concurrent.CountDownLatch(1)
    private val audio = LinkedBlockingQueue<ByteArray>()
    private val playerLock = Any()
    private var player: PcmPlayer? = null
    private var channel: RealtimeChannel? = null

    @Volatile
    private var endReason: String? = null

    fun stop() {
        stopped.set(true)
        audio.offer(ByteArray(0))
    }

    /** Blocking. Returns why the session ended. */
    fun run(): String {
        if (!focus.request(AudioFocusGate.Mode.LISTEN) { finish("Audio focus lost.") }) return "Couldn't get the microphone."
        try {
            val ch = connect(listener).also { channel = it }
            if (!ready.await(readyTimeoutMs, TimeUnit.MILLISECONDS)) {
                ch.close()
                return endReason ?: "Realtime server did not answer."
            }
            if (stopped.get()) return endReason ?: "Stopped."
            onEvent(Event.Ready)
            val playback = Thread({ playbackLoop() }, "realtime-playback").apply { isDaemon = true; start() }
            try {
                mic.start()
            } catch (e: Exception) {
                finish(e.message ?: "Car microphone unavailable.")
            }
            val buf = ByteArray(1_600) // 50 ms at 16 kHz
            try {
                while (!stopped.get()) {
                    val n = mic.read(buf, 0, buf.size)
                    if (n < 0) {
                        finish("Microphone closed.")
                        break
                    }
                    if (n == 0) continue
                    ch.sendMicAudio(buf, n)
                }
            } finally {
                mic.stop()
                stopped.set(true)
                audio.offer(ByteArray(0))
                playback.join(2_000)
                ch.close()
            }
            return endReason ?: "Stopped."
        } finally {
            synchronized(playerLock) { player?.close() }
            focus.abandon()
            onEvent(Event.Ended(endReason ?: "Stopped."))
        }
    }

    private fun finish(reason: String) {
        if (endReason == null) endReason = reason
        stop()
    }

    private fun playbackLoop() {
        while (!stopped.get()) {
            val chunk = audio.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (chunk.isEmpty()) continue
            val p = synchronized(playerLock) {
                player ?: playerFactory().also {
                    it.open(wireSampleRate)
                    player = it
                }
            }
            p.write(chunk, 0, chunk.size)
        }
    }

    private val listener = object : RealtimeListener {
        override fun onReady() = ready.countDown()

        override fun onAudio(pcm: ByteArray) {
            audio.offer(pcm)
        }

        override fun onAssistantText(delta: String) = onEvent(Event.AssistantText(delta))
        override fun onUserTranscript(text: String) = onEvent(Event.UserSaid(text))

        override fun onSpeechStarted() {
            // Barge-in: drop everything queued and restart the player so the driver is not talked over.
            audio.clear()
            synchronized(playerLock) {
                player?.stop()
                player?.close()
                player = null
            }
            channel?.cancelResponse()
        }

        override fun onResponseDone() = onEvent(Event.ResponseDone)

        override fun onError(message: String) = finish(message)

        override fun onClosed() = finish(endReason ?: "Realtime connection closed.")
    }
}
