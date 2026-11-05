package com.mdc159.sierra.obd.bt

/**
 * A thread-safe byte queue between a callback that delivers chunks (BLE notifications)
 * and a reader that wants [com.mdc159.sierra.obd.ObdTransport.read] semantics:
 * `0` on timeout, `-1` once closed and drained. Pure Kotlin so it is unit tested.
 */
class ByteInbox {
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var closed = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun offer(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            chunks.addLast(bytes.copyOf())
            lock.notifyAll()
        }
    }

    /** Bytes waiting right now. */
    fun available(): Int = synchronized(lock) { chunks.sumOf { it.size } - headOffset }

    fun read(buffer: ByteArray, timeoutMillis: Long): Int {
        if (buffer.isEmpty()) return 0
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(lock) {
            while (chunks.isEmpty()) {
                if (closed) return -1
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) return 0
                lock.wait(remaining)
            }
            var copied = 0
            while (copied < buffer.size && chunks.isNotEmpty()) {
                val head = chunks.first()
                val n = minOf(buffer.size - copied, head.size - headOffset)
                System.arraycopy(head, headOffset, buffer, copied, n)
                copied += n
                headOffset += n
                if (headOffset >= head.size) {
                    chunks.removeFirst()
                    headOffset = 0
                }
            }
            return copied
        }
    }

    /** Readers get what is queued, then `-1`. Wakes a blocked reader. */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}
