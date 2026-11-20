package com.mdc159.sierra.voice.duplex

import com.mdc159.sierra.voice.stt.MicLevels
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
    /** Full duplex: guard the mic during this much of the session's first assistant audio (see [EchoGate]). */
    fullDuplexWarmupMs: Long = 0,
    private val listener: DuplexListener,
    /** Mic frame length in milliseconds; 40 ms keeps the websocket message rate modest. */
    private val frameMs: Int = 40,
    /** Speaker rate. The service rate is used when null (no output resampling). */
    private val speakerRate: Int? = null,
    private val jitterPrefillMs: Long = 80,
    /** Diagnostic lines (mic levels and end reasons, never audio); the car screen sends them to logcat. */
    private val log: (String) -> Unit = {},
) {
    val gate = EchoGate(mode, ratio = guardRatio, fullWarmupMs = fullDuplexWarmupMs)

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val endedForwarded = AtomicBoolean(false)
    private val lifecycleLock = Any()
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
        synchronized(lifecycleLock) {
            if (stopped.get() || !running.compareAndSet(false, true)) return
            log("duplex start: requesting audio focus; mode=${gate.mode} provider=${session.provider}")
            if (!focus.request(onLoss = { stop("Audio focus lost") })) {
                log("duplex start failed: audio focus refused")
                stopped.set(true)
                cleanup()
                session.close()
                throw DuplexException("Couldn't get the audio. Try again.")
            }
        }
        try {
            if (stopped.get()) return
            session.start(::onSessionEvent)
            synchronized(lifecycleLock) {
                if (stopped.get()) { session.close(); return }
                val outRate = speakerRate ?: session.outputSampleRate
                jitter = JitterBuffer(outRate, jitterPrefillMs)
                outResampler = Resampler(session.outputSampleRate, outRate)
                sink.start(outRate)
                log("duplex speaker started rate=$outRate serviceRate=${session.outputSampleRate} prefillMs=$jitterPrefillMs")
                mic.start()
                log("duplex mic started rate=${mic.sampleRate} serviceRate=${session.inputSampleRate} frameMs=$frameMs")
                playThread = Thread(::playLoop, "duplex-play").apply { isDaemon = true; start() }
                micThread = Thread(::micLoop, "duplex-mic").apply { isDaemon = true; start() }
            }
        } catch (e: Exception) {
            log("duplex start failed: ${e.javaClass.simpleName}: ${DuplexLog.redact(e.message)}")
            val alreadyStopped = stopped.getAndSet(true)
            // The caller renders startup errors. Do not emit a misleading normal Ended first.
            if (!alreadyStopped) endedForwarded.set(true)
            synchronized(lifecycleLock) { cleanup() }
            session.close()
            if (alreadyStopped) return
            if (e is DuplexException) throw e
            throw DuplexException(e.message ?: "Couldn't start the conversation.", e)
        }
    }

    /** The driver tapped to interrupt. Cuts playback and tells the service. */
    fun tapInterrupt() {
        val played = cutPlayback()
        log("duplex tap interrupt: playback cut playedMs=$played")
        session.interrupt(played)
    }

    /** Types a message into the conversation (phone testing). */
    fun sendText(text: String) = session.sendText(text)

    fun stop() = stop("Stopped")

    private fun stop(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        val logReason = reason.takeIf { it in setOf("Stopped", "Audio focus lost", "Microphone closed", "Speaker failed", "Microphone failed. See diagnostics on the phone.") }
            ?: "voice service error"
        log("duplex stopped reason=$logReason")
        synchronized(lifecycleLock) { cleanup() }
        // Our reason first; the session's own Ended (emitted by close) is then a duplicate.
        if (endedForwarded.compareAndSet(false, true)) listener.onEvent(DuplexEvent.Ended(reason))
        session.close()
        log("duplex stop complete")
    }

    private fun cleanup() {
        val startedAt = System.currentTimeMillis()
        jitter?.close()
        runCatching { mic.stop() }.onFailure { log("duplex teardown: mic stop failed ${it.javaClass.simpleName}") }
        val micMs = System.currentTimeMillis() - startedAt
        runCatching { sink.stop() }.onFailure { log("duplex teardown: speaker stop failed ${it.javaClass.simpleName}") }
        focus.abandon()
        log("duplex teardown: mic stopped (${micMs}ms), speaker stopped, focus abandoned (${System.currentTimeMillis() - startedAt}ms total)")
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
                val queued = jitter?.queuedMs ?: 0
                val played = cutPlayback()
                log("duplex barge-in: playback cut playedMs=$played droppedQueuedMs=$queued gated=${gate.isGated}")
                session.interrupt(played)
            }
            is DuplexEvent.AssistantDone -> {
                jitter?.flush()
                if (event.cancelled) cutPlayback()
                log("duplex reply done cancelled=${event.cancelled} queuedMs=${jitter?.queuedMs ?: 0}")
            }
            is DuplexEvent.Ended -> {
                if (!stopped.compareAndSet(false, true)) return
                log("duplex ended by session")
                val forward = endedForwarded.compareAndSet(false, true)
                synchronized(lifecycleLock) { cleanup() }
                session.close()
                if (forward) listener.onEvent(event)
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
        // Levels per stretch of LEVEL_LOG_MS, so a silent or very quiet car mic shows in logcat.
        var levels = MicLevels(micRate)
        var frames = 0
        var gatedFrames = 0
        while (!stopped.get()) {
            val n = try {
                mic.read(frame)
            } catch (e: Exception) {
                log("duplex mic read failed: ${e.javaClass.simpleName}")
                stop(if (e is DuplexException) e.message ?: "Microphone failed. See diagnostics on the phone." else "Microphone failed. See diagnostics on the phone.")
                return
            }
            if (n < 0) {
                log("duplex mic closed by host; last ${levels.summary()}")
                stop("Microphone closed")
                return
            }
            if (n == 0) continue
            levels.offer(frame, n)
            if (levels.durationMs >= LEVEL_LOG_MS) {
                log("duplex mic ${levels.summary()} framesSent=$frames gated=$gatedFrames")
                levels = MicLevels(micRate)
                frames = 0
                gatedFrames = 0
            }
            val gated = gate.process(frame, n)
            frames++
            if (gate.isGated) gatedFrames++
            val out = resampler.process(gated, 0, n)
            session.sendAudio(out)
        }
    }

    private fun playLoop() {
        val buffer = jitter ?: return
        val chunk = ByteArray(Resampler.bytesForMs(20, buffer.sampleRate).coerceAtLeast(2))
        // One "playback start" per stretch of reply audio, one "playback stop" when it runs dry.
        var playing = false
        var playedBytes = 0L
        while (!stopped.get()) {
            val n = buffer.read(chunk, timeoutMs = 50)
            if (n < 0) break
            if (n == 0) {
                if (playing && buffer.isEmpty) {
                    playing = false
                    log("duplex playback stop playedMs=${Resampler.durationMs(playedBytes.toInt(), buffer.sampleRate)}")
                    playedBytes = 0
                }
                continue
            }
            if (!playing) {
                playing = true
                log("duplex playback start queuedMs=${buffer.queuedMs}")
            }
            playedBytes += n
            val warm = gate.inWarmup
            gate.onPlayback(chunk, n, Resampler.durationMs(n, buffer.sampleRate))
            if (warm && !gate.inWarmup) log("duplex full-duplex warm-up over: mic open during playback")
            try {
                sink.write(chunk, n)
            } catch (e: Exception) {
                log("duplex speaker write failed: ${e.javaClass.simpleName}")
                stop("Speaker failed")
                return
            }
        }
        if (playing) log("duplex playback stop (teardown) playedMs=${Resampler.durationMs(playedBytes.toInt(), buffer.sampleRate)}")
    }

    private companion object {
        const val LEVEL_LOG_MS = 5_000L
    }
}
