package com.mdc159.sierra.voice.duplex

/**
 * Byte FIFO between the network and the speaker for 16-bit mono PCM. Thread safe.
 *
 * Playback waits until [targetMs] of audio has arrived (or the reply is [flush]ed), then
 * plays continuously. If it runs dry before the reply is flushed, that is an underrun:
 * the network is delivering slower than real time. Instead of playing each late packet
 * as it lands (a stutter of short bursts), the target grows by [stepMs] up to
 * [maxPrefillMs] and playback waits to rebuild that much before resuming. The target
 * returns to [prefillMs] when a reply ends or is cleared.
 *
 * [playedMs] counts bytes handed to the speaker since the last [clear], which is what
 * the service needs to know when the driver interrupts.
 */
class JitterBuffer(
    val sampleRate: Int,
    val prefillMs: Long = 700,
    val maxPrefillMs: Long = 2_000,
    /** How much the target grows after each underrun. */
    val stepMs: Long = 400,
) {
    private val lock = Object()
    private var chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var queuedBytes = 0
    private var started = false
    private var flushed = false
    private var consumedBytes = 0L
    private var itemBaseBytes = 0L
    private var closed = false
    private var target = prefillMs
    private var underrunCount = 0

    val queuedMs: Long get() = synchronized(lock) { Resampler.durationMs(queuedBytes, sampleRate) }
    val playedMs: Long get() = synchronized(lock) { Resampler.durationMs(consumedBytes.toInt(), sampleRate) }

    /**
     * How much of the reply item started by [markItemStart] has been played. [playedMs] runs on
     * across replies that end without a cut (a tool call's preface, then its answer), so it cannot
     * place a truncate inside the second one.
     */
    val itemPlayedMs: Long get() = synchronized(lock) {
        Resampler.durationMs((consumedBytes - itemBaseBytes).coerceAtLeast(0).toInt(), sampleRate)
    }

    /** The audio offered next starts a new reply item. */
    fun markItemStart() = synchronized(lock) { itemBaseBytes = consumedBytes + queuedBytes }

    val isEmpty: Boolean get() = synchronized(lock) { queuedBytes == 0 }

    /** Audio playback currently waits for before (re)starting. */
    val targetMs: Long get() = synchronized(lock) { target }

    /** Times playback ran dry mid-reply since construction. */
    val underruns: Int get() = synchronized(lock) { underrunCount }

    fun offer(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            chunks.addLast(pcm)
            queuedBytes += pcm.size
            flushed = false
            lock.notifyAll()
        }
    }

    /** The reply is complete: play what is left without waiting for [targetMs]. */
    fun flush() = synchronized(lock) {
        flushed = true
        if (queuedBytes == 0) target = prefillMs
        lock.notifyAll()
    }

    /**
     * Fills [dest] with the next bytes. Blocks until data is available or [timeoutMs]
     * passes. Returns the byte count, 0 on timeout, or -1 after [close].
     */
    fun read(dest: ByteArray, timeoutMs: Long = 50): Int {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed && !ready()) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0) return 0
                lock.wait(wait)
            }
            if (closed) return -1
            started = true
            var n = 0
            while (n < dest.size && chunks.isNotEmpty()) {
                val head = chunks.first()
                val take = minOf(dest.size - n, head.size - headOffset)
                System.arraycopy(head, headOffset, dest, n, take)
                n += take
                headOffset += take
                if (headOffset == head.size) {
                    chunks.removeFirst()
                    headOffset = 0
                }
            }
            queuedBytes -= n
            consumedBytes += n
            if (queuedBytes == 0) {
                started = false
                if (flushed) {
                    target = prefillMs
                } else {
                    underrunCount++
                    target = minOf(maxPrefillMs, target + stepMs)
                }
            }
            return n
        }
    }

    private fun ready(): Boolean {
        if (queuedBytes == 0) return false
        if (started || flushed) return true
        return Resampler.durationMs(queuedBytes, sampleRate) >= target
    }

    /** Drops everything queued and resets [playedMs]. Used on interruption. */
    fun clear() = synchronized(lock) {
        chunks = ArrayDeque()
        headOffset = 0
        queuedBytes = 0
        consumedBytes = 0
        itemBaseBytes = 0
        started = false
        flushed = false
        target = prefillMs
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}
