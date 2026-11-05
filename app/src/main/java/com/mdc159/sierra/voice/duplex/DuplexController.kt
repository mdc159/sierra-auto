package com.mdc159.sierra.voice.duplex

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires one [DuplexSession] to a microphone and a speaker:
 *
 *  mic → [EchoGate] → [Resampler] (mic rate → service rate) → session
 *  session audio → [Resampler] (service rate → speaker rate) → [JitterBuffer] → speaker
 *
 * Interruptions: when the service hears the driver ([DuplexEvent.UserStartedSpeaking]),
 * or the driver taps ([tapInterrupt]), playback is cut, the jitter buffer is emptied and
 * the session is told how much had been played.
 *
 * Everything is injected so the whole thing runs in a JVM unit test with fakes.
 */
class DuplexController(
    private val session: DuplexSession,
    private val mic: MicSource,
    private val sink: PcmSink,
    private val focus: FocusHandle,
    mode: DuplexMode,
    guardRatio: Double = 2.0,
    private val listener: DuplexListener,
    /** Mic frame length in milliseconds; 40 ms keeps the websocket message rate modest. */
    private val frameMs: Int = 40,
    /** Speaker rate. The service rate is used when null (no output resampling). */
    private val speakerRate: Int? = null,
    private val jitterPrefillMs: Long = 80,
) {
    val gate = EchoGate(mode, ratio = guardRatio)

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val endedForwarded = AtomicBoolean(false)
    private var micThread: Thread? = null
    private var playThread: Thread? = null

    @Volatile
    private var jitter: JitterBuffer? = null

    @Volatile
    private var outResampler: Resampler? = null

    val isRunning: Boolean get() = running.get() && !stopped.get()

    /**
     * Takes focus, connects, then starts the mic and speaker threads. Blocking until the
     * handshake is done; throws [DuplexException] when it cannot start. Call off the main thread.
     */
    @Throws(DuplexException::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (!focus.request(onLoss = { stop("Audio focus lost") })) {
            running.set(false)
            throw DuplexException("Couldn't get the audio. Try again.")
        }
        try {
            session.start(::onSessionEvent)
            val outRate = speakerRate ?: session.outputSampleRate
            val buffer = JitterBuffer(outRate, jitterPrefillMs)
            jitter = buffer
            outResampler = Resampler(session.outputSampleRate, outRate)
            sink.start(outRate)
            mic.start()
        } catch (e: DuplexException) {
            cleanup()
            throw e
        } catch (e: Exception) {
            cleanup()
            throw DuplexException(e.message ?: "Couldn't start the conversation.", e)
        }
        playThread = Thread(::playLoop, "duplex-play").apply { isDaemon = true; start() }
        micThread = Thread(::micLoop, "duplex-mic").apply { isDaemon = true; start() }
    }

    /** The driver tapped to interrupt. Cuts playback and tells the service. */
    fun tapInterrupt() {
        val played = cutPlayback()
        session.interrupt(played)
    }

    /** Types a message into the conversation (phone testing). */
    fun sendText(text: String) = session.sendText(text)

    fun stop() = stop("Stopped")

    private fun stop(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        cleanup()
        // Our reason first; the session's own Ended (emitted by close) is then a duplicate.
        if (endedForwarded.compareAndSet(false, true)) listener.onEvent(DuplexEvent.Ended(reason))
        session.close()
    }

    private fun cleanup() {
        jitter?.close()
        runCatching { mic.stop() }
        runCatching { sink.stop() }
        focus.abandon()
    }

    /** Empties the speaker path. Returns how many ms of the current reply were played. */
    private fun cutPlayback(): Long {
        val buffer = jitter ?: return 0
        val played = buffer.playedMs
        buffer.clear()
        sink.discard()
        outResampler?.reset()
        gate.onPlaybackStopped()
        return played
    }

    private fun onSessionEvent(event: DuplexEvent) {
        when (event) {
            is DuplexEvent.AssistantAudio -> {
                val resampled = outResampler?.process(event.pcm) ?: event.pcm
                jitter?.offer(resampled)
            }
            is DuplexEvent.UserStartedSpeaking -> {
                // The service already cancelled its reply; match it locally and tell it where we were.
                val played = cutPlayback()
                session.interrupt(played)
            }
            is DuplexEvent.AssistantDone -> {
                jitter?.flush()
                if (event.cancelled) cutPlayback()
            }
            is DuplexEvent.Ended -> {
                if (stopped.compareAndSet(false, true)) cleanup()
                if (endedForwarded.compareAndSet(false, true)) listener.onEvent(event)
                return
            }
            is DuplexEvent.Error -> if (event.fatal) stop(event.message)
            else -> Unit
        }
        listener.onEvent(event)
    }

    private fun micLoop() {
        val micRate = mic.sampleRate
        val frame = ByteArray(Resampler.bytesForMs(frameMs.toLong(), micRate).coerceAtLeast(2))
        val resampler = Resampler(micRate, session.inputSampleRate)
        while (!stopped.get()) {
            val n = try {
                mic.read(frame)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) {
                stop("Microphone closed")
                return
            }
            if (n == 0) continue
            val gated = gate.process(frame, n)
            val out = resampler.process(gated, 0, n)
            session.sendAudio(out)
        }
    }

    private fun playLoop() {
        val buffer = jitter ?: return
        val chunk = ByteArray(Resampler.bytesForMs(20, buffer.sampleRate).coerceAtLeast(2))
        while (!stopped.get()) {
            val n = buffer.read(chunk, timeoutMs = 50)
            if (n < 0) return
            if (n == 0) continue
            gate.onPlayback(chunk, n)
            try {
                sink.write(chunk, n)
            } catch (e: Exception) {
                stop("Speaker failed")
                return
            }
        }
    }
}
