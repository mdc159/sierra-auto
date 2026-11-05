package com.mdc159.sierra.voice.duplex

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.core.content.ContextCompat

/** Speech attributes shared by mic focus and playback, per the constraints doc (USAGE_ASSISTANT). */
internal val speechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Exclusive transient focus for the whole conversation: media pauses while Sierra listens
 * and talks, and comes back when the session ends. Focus loss ends the session.
 */
class AndroidFocus(context: Context) : FocusHandle {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(speechAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLoss()
            }
            .build()
        request = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }
}

/**
 * The phone's microphone through the voice-communication path, which turns on the
 * platform's acoustic echo canceller and noise suppressor where the device has them.
 * This is what "Try it on this phone" uses. Needs RECORD_AUDIO.
 */
class PhoneMic(override val sampleRate: Int = 16_000) : MicSource {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw DuplexException("This phone cannot record at $sampleRate Hz.")
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, sampleRate * 2 / 5), // at least 200 ms
            )
        } catch (e: Exception) {
            throw DuplexException("Microphone unavailable.", e)
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw DuplexException("Microphone unavailable.")
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.also { it.setEnabled(true) } }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(r.audioSessionId)?.also { it.setEnabled(true) } }.getOrNull()
        }
        r.startRecording()
        record = r
    }

    /** True when the platform echo canceller is attached (for the settings screen). */
    val echoCancellerActive: Boolean get() = aec?.enabled == true

    override fun read(dest: ByteArray): Int {
        val r = record ?: return -1
        val n = r.read(dest, 0, dest.size)
        return if (n < 0) -1 else n
    }

    override fun stop() {
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        aec?.release()
        aec = null
        ns?.release()
        ns = null
    }
}

/**
 * The car microphone over Android Auto. 16 kHz per CarAudioRecord. A negative read means
 * the driver dismissed the mic on the car screen, which ends the session.
 */
class CarMicSource(private val carContext: CarContext) : MicSource {
    override val sampleRate: Int = CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE
    private var record: CarAudioRecord? = null

    override fun start() {
        // CarAudioRecord requires RECORD_AUDIO; the screen checks first, but never rely on it.
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw DuplexException("Allow the microphone in Sierra on the phone.")
        }
        val r = try {
            CarAudioRecord.create(carContext)
        } catch (e: Exception) {
            throw DuplexException("Car microphone unavailable.", e)
        }
        r.startRecording()
        record = r
    }

    override fun read(dest: ByteArray): Int {
        val r = record ?: return -1
        val want = minOf(dest.size, CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        return r.read(dest, 0, want)
    }

    override fun stop() {
        record?.let { runCatching { it.stopRecording() } }
        record = null
    }
}

/** Low-latency streaming playback through AudioTrack with the assistant speech attributes. */
class AudioTrackSink : PcmSink {
    private var track: AudioTrack? = null

    override fun start(sampleRate: Int) {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(speechAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2 / 10)) // >= 100 ms
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            throw DuplexException("Speaker unavailable.", e)
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw DuplexException("Speaker unavailable.")
        }
        t.play()
        track = t
    }

    override fun write(pcm: ByteArray, length: Int) {
        val t = track ?: return
        var off = 0
        while (off < length) {
            val n = t.write(pcm, off, length - off, AudioTrack.WRITE_BLOCKING)
            if (n < 0) throw DuplexException("Speaker write failed ($n).")
            off += n
        }
    }

    override fun discard() {
        track?.let {
            runCatching {
                it.pause()
                it.flush()
                it.play()
            }
        }
    }

    override fun stop() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            it.release()
        }
        track = null
    }
}
