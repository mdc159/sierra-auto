package com.mdc159.sierra.voice.duplex

import kotlin.math.sqrt

/**
 * The guarded-mode gate. In a truck the speakers feed the microphone, so while the
 * assistant speaks the mic hears the assistant. Sending that to the service makes it
 * interrupt itself. This gate replaces mic frames with silence while playback is
 * active (and for [hangoverMs] after), unless the mic is at least [ratio] times louder
 * than what is being played, which means the driver is talking over it.
 *
 * In [DuplexMode.FULL_DUPLEX] the gate is open and frames pass untouched.
 *
 * Pure JVM, so the thresholds can be tuned by unit tests and then by the truck test plan
 * in docs/DUPLEX.md.
 */
class EchoGate(
    val mode: DuplexMode,
    private val ratio: Double = 2.0,
    private val hangoverMs: Long = 250,
    /** Below this mic RMS nothing counts as speech, whatever the playback level. */
    private val floorRms: Double = 400.0,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var playbackRms = 0.0

    @Volatile
    private var lastPlaybackAt = 0L

    /** True when the last frame was replaced by silence. */
    @Volatile
    var isGated = false
        private set

    /** Call with each chunk the speaker is about to play, so the gate knows the level. */
    fun onPlayback(pcm: ByteArray, length: Int = pcm.size) {
        if (length <= 0) return
        playbackRms = rms(pcm, length)
        lastPlaybackAt = clock()
    }

    /** Call when playback is cut or the reply ends, so the gate opens after the hangover. */
    fun onPlaybackStopped() {
        playbackRms = 0.0
    }

    /**
     * Returns the frame to send to the service: [pcm] itself when the gate is open, or a
     * zero-filled frame of the same length when gated.
     */
    fun process(pcm: ByteArray, length: Int = pcm.size): ByteArray {
        val frame = if (length == pcm.size) pcm else pcm.copyOf(length)
        if (mode == DuplexMode.FULL_DUPLEX) {
            isGated = false
            return frame
        }
        val speaking = clock() - lastPlaybackAt <= hangoverMs && lastPlaybackAt != 0L
        if (!speaking) {
            isGated = false
            return frame
        }
        val mic = rms(frame, length)
        val loudEnough = mic >= floorRms && mic >= playbackRms * ratio
        isGated = !loudEnough
        return if (isGated) ByteArray(length) else frame
    }

    companion object {
        fun rms(buf: ByteArray, length: Int): Double {
            val samples = length / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            return sqrt(sum / samples)
        }
    }
}
