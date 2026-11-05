package com.mdc159.sierra.voice.duplex

/**
 * Byte FIFO between the network and the speaker for 16-bit mono PCM. Playback waits
 * until [prefillMs] of audio has arrived (or the stream is flushed) so a slow first
 * packet does not stutter, then runs the buffer dry. Thread safe.
 *
 * [playedMs] counts bytes handed to the speaker since the last [clear], which is what
 * the service needs to know when the driver interrupts.
 */
class JitterBuffer(val sampleRate: Int, val prefillMs: Long = 80) {
    private val lock = Object()
    private var chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var queuedBytes = 0
    private var started = false
    private var flushed = false
    private var consumedBytes = 0L
    private var closed = false

    val queuedMs: Long get() = synchronized(lock) { Resampler.durationMs(queuedBytes, sampleRate) }
    val playedMs: Long get() = synchronized(lock) { Resampler.durationMs(consumedBytes.toInt(), sampleRate) }
    val isEmpty: Boolean get() = synchronized(lock) { queuedBytes == 0 }

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

    /** Lets playback start before [prefillMs] has arrived (end of a short reply). */
    fun flush() = synchronized(lock) {
        flushed = true
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
            if (queuedBytes == 0) started = false
            return n
        }
    }

    private fun ready(): Boolean {
        if (queuedBytes == 0) return false
        if (started || flushed) return true
        return Resampler.durationMs(queuedBytes, sampleRate) >= prefillMs
    }

    /** Drops everything queued and resets [playedMs]. Used on interruption. */
    fun clear() = synchronized(lock) {
        chunks = ArrayDeque()
        headOffset = 0
        queuedBytes = 0
        consumedBytes = 0
        started = false
        flushed = false
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}
