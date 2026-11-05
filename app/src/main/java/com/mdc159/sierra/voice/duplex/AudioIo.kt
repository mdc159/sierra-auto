package com.mdc159.sierra.voice.duplex

/**
 * A continuous microphone. [read] blocks until [dest] has data and returns the byte
 * count, or -1 when the source is gone (the driver dismissed the car mic, or the
 * device was lost). 16-bit little-endian mono PCM at [sampleRate].
 */
interface MicSource {
    val sampleRate: Int

    @Throws(DuplexException::class)
    fun start()

    fun read(dest: ByteArray): Int

    fun stop()
}

/** A speaker that takes 16-bit mono PCM. [write] blocks while the device drains. */
interface PcmSink {
    @Throws(DuplexException::class)
    fun start(sampleRate: Int)

    fun write(pcm: ByteArray, length: Int)

    /** Drops anything still queued in the device, for an interruption. */
    fun discard()

    fun stop()
}

/**
 * Audio focus, the way docs/android-auto-constraints.md section 7 describes it: take
 * exclusive transient focus before the microphone opens, give it back when done, stop on
 * loss. Abstracted so the controller can be tested without Android.
 */
interface FocusHandle {
    /** Returns false when focus was refused (a phone call, for example). */
    fun request(onLoss: () -> Unit): Boolean

    fun abandon()
}
