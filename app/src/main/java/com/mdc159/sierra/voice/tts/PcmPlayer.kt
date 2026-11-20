package com.mdc159.sierra.voice.tts

import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays 16-bit little-endian mono PCM as it arrives. [write] blocks while the buffer is
 * full, so a producer can simply push bytes as fast as the network delivers them.
 */
interface PcmPlayer {
    /** Prepares playback at [sampleRate] Hz. Must be called before the first [write]. */
    fun open(sampleRate: Int)

    /** Queues audio. Blocks until it has been accepted. Returns false if playback was stopped. */
    fun write(data: ByteArray, offset: Int, length: Int): Boolean

    /** Blocks until everything written has been heard (or [stop] is called). */
    fun finish()

    /** Stops immediately and discards queued audio. Safe to call from any thread, and twice. */
    fun stop()

    /** Releases the underlying track. */
    fun close()
}

/** [PcmPlayer] on an [AudioTrack] in streaming mode with assistant audio attributes. */
class AudioTrackPlayer(
    private val sliceMs: Int = 100,
) : PcmPlayer {

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false
    private var sampleRate = 24_000
    private var framesWritten = 0L

    override fun open(sampleRate: Int) {
        this.sampleRate = sampleRate
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Half a second of audio, or the minimum, whichever is larger: deep enough to ride
        // out network jitter, shallow enough that stop() is heard within a slice.
        val buffer = maxOf(minBuffer, sampleRate) // sampleRate bytes = 0.5 s of 16-bit mono
        val t = AudioTrack.Builder()
            .setAudioAttributes(AndroidAudioFocus.ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        framesWritten = 0
        t.play()
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        val t = track ?: return false
        val sliceBytes = (sampleRate * 2 * sliceMs / 1000).coerceAtLeast(2)
        var pos = offset
        val end = offset + length
        while (pos < end) {
            if (stopped) return false
            val n = minOf(sliceBytes, end - pos)
            val written = t.write(data, pos, n, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return false
            if (written == 0) {
                if (stopped) return false
                Thread.sleep(5)
                continue
            }
            pos += written
            framesWritten += written / 2
        }
        return true
    }

    override fun finish() {
        val t = track ?: return
        if (stopped) return
        // AudioTrack.stop() in streaming mode lets queued audio play out, so wait until the
        // head position reaches what we wrote, with a cap in case the position stalls.
        val deadline = System.currentTimeMillis() + framesWritten * 1000 / sampleRate + 1_000
        try {
            while (!stopped && System.currentTimeMillis() < deadline) {
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head >= framesWritten) break
                Thread.sleep(20)
            }
        } catch (e: IllegalStateException) {
            // Track released underneath us; nothing left to wait for.
        }
        if (!stopped) runCatching { t.stop() }
    }

    override fun stop() {
        stopped = true
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
            t.stop()
        }
    }

    override fun close() {
        stopped = true
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush() }
        runCatching { t.release() }
    }
}
