package com.mdc159.sierra.voice.stt

/**
 * A source of 16-bit little-endian mono PCM: the car microphone in production, a scripted
 * queue in tests. All methods are blocking and are called off the main thread.
 */
interface MicSource {
    val sampleRate: Int

    /** Opens the microphone. Throws with a short, screen-ready message if it cannot. */
    fun start()

    /**
     * Reads up to [length] bytes. Returns the count, 0 if nothing is available yet, or -1
     * when the host has ended the microphone session (the driver dismissed the mic).
     */
    fun read(buf: ByteArray, offset: Int, length: Int): Int

    /** Closes the microphone. Safe to call more than once. */
    fun stop()
}
