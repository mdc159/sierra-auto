package com.mdc159.sierra.voice

import com.mdc159.sierra.voice.convo.ConversationAgent
import com.mdc159.sierra.voice.stt.MicSource
import com.mdc159.sierra.voice.stt.Transcriber
import com.mdc159.sierra.voice.tts.AudioFocusGate
import com.mdc159.sierra.voice.tts.PcmPlayer
import com.mdc159.sierra.voice.tts.SpeakResult
import com.mdc159.sierra.voice.tts.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Records focus requests and releases; optionally refuses them. */
class FakeAudioFocus(var grant: Boolean = true) : AudioFocusGate {
    val requests = Collections.synchronizedList(mutableListOf<AudioFocusGate.Mode>())
    var abandons = 0
    var held = false
    private var onLoss: (() -> Unit)? = null

    override fun request(mode: AudioFocusGate.Mode, onLoss: () -> Unit): Boolean {
        requests += mode
        if (!grant) return false
        held = true
        this.onLoss = onLoss
        return true
    }

    override fun abandon() {
        synchronized(this) {
            abandons++
            held = false
        }
    }

    /** Simulates the system taking focus away (a phone call). */
    fun loseFocus() {
        onLoss?.invoke()
    }
}

/** Collects written PCM and timestamps so tests can prove playback started early. */
class FakePcmPlayer(private val playMs: Long = 0) : PcmPlayer {
    val audio = ByteArrayOutputStream()
    var sampleRate = -1
    var opened = false
    var finished = false
    @Volatile var stopped = false
    var closed = false
    val writeTimesNanos = Collections.synchronizedList(mutableListOf<Long>())
    val firstWrite = CountDownLatch(1)

    override fun open(sampleRate: Int) {
        this.sampleRate = sampleRate
        opened = true
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        if (stopped) return false
        writeTimesNanos += System.nanoTime()
        synchronized(audio) { audio.write(data, offset, length) }
        firstWrite.countDown()
        if (playMs > 0) Thread.sleep(playMs)
        return !stopped
    }

    override fun finish() {
        finished = true
    }

    override fun stop() {
        stopped = true
    }

    override fun close() {
        closed = true
    }

    fun bytes(): ByteArray = synchronized(audio) { audio.toByteArray() }
}

/** Scripted speech output: records what it was asked to say and can be interrupted. */
class FakeSpeechOutput : SpeechOutput {
    override val name = "fake"
    val spoken = Collections.synchronizedList(mutableListOf<String>())
    var stops = 0
    var closed = false
    var failWith: String? = null

    /** When set, [speak] suspends until [stop] is called (or [release] completes it). */
    var holdUntilStopped = false
    private var holding: CompletableDeferred<SpeakResult>? = null
    val speaking = CountDownLatch(1)

    override suspend fun speak(text: String): SpeakResult {
        spoken += text
        failWith?.let { return SpeakResult.Failed(it) }
        if (!holdUntilStopped) return SpeakResult.Completed
        val d = CompletableDeferred<SpeakResult>()
        holding = d
        speaking.countDown()
        return try {
            d.await()
        } catch (e: CancellationException) {
            throw e
        }
    }

    override fun stop() {
        stops++
        holding?.complete(SpeakResult.Interrupted)
        holding = null
    }

    fun release() {
        holding?.complete(SpeakResult.Completed)
        holding = null
    }

    override fun close() {
        closed = true
    }
}

/**
 * A microphone fed from a queue of PCM chunks. Each chunk is handed to the reader as one
 * read. A null chunk means the host ended the session (read returns -1). When the queue is
 * empty the read blocks until more audio is queued or [endSession] is called.
 */
class FakeMic(override val sampleRate: Int = 16_000) : MicSource {
    private val queue = LinkedBlockingQueue<ByteArray?>()
    private val end = ByteArray(0)
    var started = 0
    var stopped = 0
    var failStart: Exception? = null

    override fun start() {
        failStart?.let { throw it }
        started++
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int {
        val chunk = queue.poll(5, TimeUnit.SECONDS) ?: return 0
        if (chunk === end) {
            queue.put(end) // keep returning -1
            return -1
        }
        val n = minOf(length, chunk.size)
        System.arraycopy(chunk, 0, buf, offset, n)
        return n
    }

    override fun stop() {
        stopped++
    }

    fun feed(chunk: ByteArray) = queue.put(chunk)

    fun endSession() = queue.put(end)

    /** Queues [ms] of audio at [rms] amplitude, in [chunkMs] pieces. */
    fun feedMs(ms: Int, rms: Int, chunkMs: Int = 20) {
        var left = ms
        while (left > 0) {
            val m = minOf(chunkMs, left)
            feed(Pcm16.tone(m, rms, sampleRate))
            left -= m
        }
    }
}

/** Scripted transcriber. */
class FakeTranscriber(vararg results: String) : Transcriber {
    private val queue = ArrayDeque(results.toList())
    val received = mutableListOf<ByteArray>()
    var failWith: Exception? = null

    override suspend fun transcribe(pcm: ByteArray, sampleRate: Int): String {
        received += pcm
        failWith?.let { throw it }
        return queue.removeFirstOrNull() ?: ""
    }
}

/** Scripted agent. */
class FakeAgent(private val reply: (String) -> String = { "You said $it." }) : ConversationAgent {
    val asked = mutableListOf<String>()
    var failWith: Exception? = null

    override suspend fun reply(userText: String): String {
        asked += userText
        failWith?.let { throw it }
        return reply(userText)
    }
}

/** Synthetic 16-bit PCM for tests. */
object Pcm16 {
    /** A square wave of the given RMS (peak = rms for a square wave), [ms] long. */
    fun tone(ms: Int, rms: Int, sampleRate: Int = 16_000): ByteArray {
        val samples = sampleRate * ms / 1000
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if ((i / 20) % 2 == 0) rms else -rms
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun silence(ms: Int, sampleRate: Int = 16_000): ByteArray = ByteArray(sampleRate * ms / 1000 * 2)
}
