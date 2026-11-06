package com.mdc159.sierra.voice.diagnostics

import android.content.Context
import androidx.car.app.CarContext
import com.mdc159.sierra.voice.CarMic
import com.mdc159.sierra.voice.duplex.DuplexException
import com.mdc159.sierra.voice.duplex.MicSource
import com.mdc159.sierra.voice.duplex.PhoneMic
import com.mdc159.sierra.voice.stt.MicLevels
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A single owner across Talk, Conversation, the phone test and the car test. */
internal object MicLease {
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any): Boolean { if (owner != null) return false; owner = token; return true }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
}

object Microphones {
    fun selected(car: CarContext): MicSource {
        val source = MicSettings.source(car)
        return if (source == MicrophoneSource.PHONE) phone(car, requireService = true) else {
            if (!CarMic.isSupported(car)) throw DuplexException("This car cannot supply audio to Sierra. Select Phone microphone in Microphone & diagnostics.")
            ObservedMic(car, "car", com.mdc159.sierra.voice.duplex.CarMicSource(car))
        }
    }

    fun phone(context: Context, requireService: Boolean = false): MicSource = ObservedMic(
        context, "phone", PhoneMic(context = context.applicationContext),
        beforeStart = {
            if (requireService && !PhoneMicService.ready) throw DuplexException(
                "Enable Phone microphone in Sierra's Microphone & diagnostics screen on the phone first.",
            )
        },
        phone = true,
        requireService = requireService,
    )

    fun forTalk(factory: () -> MicSource): com.mdc159.sierra.voice.stt.MicSource = object : com.mdc159.sierra.voice.stt.MicSource {
        @Volatile private var current: MicSource? = null
        override val sampleRate get() = current?.sampleRate ?: 16_000
        override fun start() { factory().also { current = it }.start() }
        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            val mic = current ?: return -1
            if (offset == 0 && length == buf.size) return mic.read(buf)
            val tmp = ByteArray(length)
            return mic.read(tmp).also { if (it > 0) System.arraycopy(tmp, 0, buf, offset, it) }
        }
        override fun stop() { val mic = current; current = null; mic?.stop() }
    }
}

/** Stops stalled reads and persists only level statistics. Never saves microphone audio. */
internal class ObservedMic(
    context: Context,
    private val source: String,
    private val delegate: MicSource,
    private val beforeStart: () -> Unit = {},
    private val phone: Boolean = false,
    private val requireService: Boolean = false,
    private val stallTimeoutMs: Long = 5_000,
) : MicSource {
    private val app = context.applicationContext
    private val lock = Any()
    private val terminal = AtomicBoolean(false)
    @Volatile private var open = false
    @Volatile private var lastData = 0L
    @Volatile private var stalled = false
    private var watcher: java.util.concurrent.ScheduledExecutorService? = null
    private var levels = MicLevels(delegate.sampleRate)
    override val sampleRate get() = delegate.sampleRate

    override fun start() = synchronized(lock) {
        if (terminal.get()) throw DuplexException("Microphone session was stopped. Try again.")
        if (open) return@synchronized
        beforeStart()
        if (!MicLease.acquire(this)) throw DuplexException("Sierra's microphone is busy. Stop the other voice session, then try again.")
        try {
            if (phone) PhoneMicService.register(this, requireService)
            delegate.start()
            open = true
            levels = MicLevels(sampleRate)
            lastData = System.nanoTime()
            VoiceDiagnostics.opened(app, source)
            watcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mic-watchdog").apply { isDaemon = true } }
            watcher!!.scheduleAtFixedRate({
                if (System.nanoTime() - lastData > TimeUnit.MILLISECONDS.toNanos(stallTimeoutMs)) {
                    stalled = true
                    VoiceDiagnostics.event(app, "Microphone", "$source delivered no audio for 5 seconds")
                    stop()
                }
            }, minOf(1_000, stallTimeoutMs), minOf(1_000, stallTimeoutMs), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            runCatching { delegate.stop() }
            if (phone) PhoneMicService.unregister(this)
            MicLease.release(this)
            VoiceDiagnostics.failure(app, "Microphone open", e)
            throw e
        }
    }

    override fun read(dest: ByteArray): Int {
        if (stalled) throw DuplexException("No audio arrived from the $source microphone. Open Microphone & diagnostics on the phone.")
        if (!open || terminal.get()) return -1
        val n = delegate.read(dest)
        if (stalled) throw DuplexException("No audio arrived from the $source microphone. Open Microphone & diagnostics on the phone.")
        if (n > 0) {
            lastData = System.nanoTime()
            VoiceDiagnostics.level(com.mdc159.sierra.voice.Pcm.rms(dest, n))
            synchronized(lock) {
                levels.offer(dest, n)
                if (levels.durationMs >= 1_000) {
                    VoiceDiagnostics.mic(app, source, levels)
                    levels = MicLevels(sampleRate)
                }
            }
        } else if (n == 0) Thread.sleep(10)
        return n
    }

    override fun stop() = synchronized(lock) {
        terminal.set(true)
        watcher?.shutdownNow()
        watcher = null
        if (open) {
            open = false
            try { delegate.stop() } finally {
                if (levels.durationMs > 0) VoiceDiagnostics.mic(app, source, levels)
                VoiceDiagnostics.closed(app)
                if (phone) PhoneMicService.unregister(this)
                MicLease.release(this)
            }
        }
    }
}
