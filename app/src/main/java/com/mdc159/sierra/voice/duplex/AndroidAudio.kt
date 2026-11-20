package com.mdc159.sierra.voice.duplex

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.core.content.ContextCompat
import com.mdc159.sierra.voice.diagnostics.VoiceDiagnostics

/** Speech attributes shared by mic focus and playback, per the constraints doc (USAGE_ASSISTANT). */
internal val speechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Voice-call attributes for the phone test: playback on the communication path is what the
 * platform echo canceller takes as its reference while the mic records VOICE_COMMUNICATION.
 */
internal val voiceCallAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Exclusive transient focus for the whole conversation: media pauses while Sierra listens
 * and talks, and comes back when the session ends. Focus loss ends the session.
 */
class AndroidFocus(
    context: Context,
    private val attributes: AudioAttributes = speechAttributes,
) : FocusHandle {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
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
 * The phone test's audio session: [AndroidFocus] with voice-call attributes, plus the
 * platform's communication mode for the session's lifetime, on the built-in loudspeaker.
 *
 * Measured 2026-11-20 on a Galaxy A54: in MODE_NORMAL, with USAGE_ASSISTANT playback, the
 * VOICE_COMMUNICATION record session got only the AOSP software echo canceller, the model
 * heard its own voice through the loudspeaker and answered itself. In MODE_IN_COMMUNICATION
 * the device's voice-call processing has the playback as its echo reference.
 *
 * The controller calls [abandon] on every ending (stop, error, focus loss, failed start), so
 * the previous mode and route are always put back. The car keeps plain [AndroidFocus]: there
 * the audio goes to the head unit, and a phone-call mode would be wrong.
 */
class PhoneCallAudio(context: Context) : FocusHandle {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val focus = AndroidFocus(context, voiceCallAttributes)
    private var previousMode: Int? = null
    private var routed = false
    private var raisedFrom: Int? = null
    private var raisedTo = 0

    override fun request(onLoss: () -> Unit): Boolean {
        if (!focus.request(onLoss)) return false
        previousMode = audio.mode
        runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { DuplexLog.w("phone audio: communication mode refused ${it.javaClass.simpleName}") }
        routed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                speaker != null && audio.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = true
                true
            }
        }.getOrDefault(false)
        if (routed) awaitLoudspeakerRoute()
        matchAssistantVolume()
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.communicationDevice?.type?.toString() ?: "none" else "speakerphone"
        DuplexLog.i(
            "phone audio: mode=${modeName(audio.mode)} (was ${modeName(previousMode ?: -1)}) loudspeaker=$routed commDeviceType=$device " +
                "voiceCallVolume=${audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)} " +
                "musicVolume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",
        )
        return true
    }

    override fun abandon() {
        val mode = previousMode
        previousMode = null
        if (mode != null) {
            raisedFrom?.let { from ->
                // Put back the owner's call volume, unless they changed it during the session.
                if (audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) == raisedTo) {
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, from, 0) }
                }
            }
            raisedFrom = null
            if (routed) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        audio.clearCommunicationDevice()
                    } else {
                        @Suppress("DEPRECATION")
                        audio.isSpeakerphoneOn = false
                    }
                }
            }
            routed = false
            runCatching { audio.mode = mode }
            DuplexLog.i("phone audio: restored mode=${modeName(audio.mode)}")
        }
        focus.abandon()
    }

    /**
     * The route change is applied asynchronously; until it lands, the voice-call volume read
     * here is the earpiece's (15/15 on the bench phone), not the loudspeaker's. Wait for it.
     */
    private fun awaitLoudspeakerRoute() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Thread.sleep(200) // no per-attributes route query before API 33
            return
        }
        val deadline = System.currentTimeMillis() + ROUTE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val onSpeaker = runCatching {
                audio.getAudioDevicesForAttributes(voiceCallAttributes).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }.getOrDefault(true)
            if (onSpeaker) return
            Thread.sleep(20)
        }
        DuplexLog.w("phone audio: loudspeaker route not confirmed after ${ROUTE_WAIT_MS}ms")
    }

    /**
     * On this path the reply plays on the voice-call stream, whose speakerphone level is often
     * far below the assistant level the owner set (1/15 against 10/15 on the bench phone). Raise
     * it to the assistant level for the session, so the reply is as loud as before; never lower it.
     */
    private fun matchAssistantVolume() {
        runCatching {
            val callMax = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val assistant = runCatching { audio.getStreamVolume(STREAM_ASSISTANT) to audio.getStreamMaxVolume(STREAM_ASSISTANT) }
                .getOrNull()?.takeIf { it.second > 0 }
                ?: (audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val target = Math.round(assistant.first.toDouble() / assistant.second * callMax).toInt().coerceIn(1, callMax)
            val before = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            if (before < target) {
                audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
                raisedFrom = before
                raisedTo = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                DuplexLog.i("phone audio: voice-call volume raised $before -> $raisedTo/$callMax to match assistant ${assistant.first}/${assistant.second}")
            }
        }.onFailure { DuplexLog.w("phone audio: volume not adjusted ${it.javaClass.simpleName}") }
    }

    private fun modeName(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        else -> mode.toString()
    }

    private companion object {
        /** AudioSystem.STREAM_ASSISTANT; hidden in the SDK, where USAGE_ASSISTANT playback goes. */
        const val STREAM_ASSISTANT = 11

        const val ROUTE_WAIT_MS = 1_000L
    }
}

/**
 * The phone's microphone through the voice-communication path, which turns on the
 * platform's acoustic echo canceller and noise suppressor where the device has them.
 * This is what "Try it on this phone" uses. Needs RECORD_AUDIO.
 */
class PhoneMic(override val sampleRate: Int = 16_000, private val context: Context? = null) : MicSource {
    @Volatile
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
        try {
            val builtIn = context?.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtIn != null) r.setPreferredDevice(builtIn)
            r.startRecording()
            record = r
            DuplexLog.i(
                "phone mic: source=VOICE_COMMUNICATION routedType=${r.routedDevice?.type ?: "unknown"} " +
                    "aec=${effectState(AcousticEchoCanceler.isAvailable(), aec?.enabled, aec?.descriptor?.name, aec?.descriptor?.implementor)} " +
                    "ns=${effectState(NoiseSuppressor.isAvailable(), ns?.enabled, ns?.descriptor?.name, ns?.descriptor?.implementor)}",
            )
            context?.let {
                VoiceDiagnostics.event(it, "Phone audio route", "requested=${if (builtIn != null) "built-in" else "system default"} actualType=${r.routedDevice?.type ?: "unknown"} silenced=${r.activeRecordingConfiguration?.isClientSilenced ?: "unknown"}")
            }
        } catch (e: Exception) {
            r.release()
            aec?.release(); aec = null
            ns?.release(); ns = null
            throw DuplexException("Microphone unavailable.", e)
        }
    }

    private fun effectState(available: Boolean, enabled: Boolean?, name: String?, implementor: String?): String =
        if (!available) "unavailable" else "${if (enabled == true) "on" else "off"}(${name ?: "?"}/${implementor ?: "?"})"

    /** True when the platform echo canceller is attached (for the settings screen). */
    val echoCancellerActive: Boolean get() = aec?.enabled == true

    override fun read(dest: ByteArray): Int {
        val r = record ?: return -1
        val n = r.read(dest, 0, dest.size)
        return if (n < 0) -1 else n
    }

    override fun stop() {
        val current = record
        record = null
        current?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
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
    @Volatile private var record: CarAudioRecord? = null

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
        record = r
        try { r.startRecording() } catch (e: Exception) { stop(); throw e }
    }

    override fun read(dest: ByteArray): Int {
        val r = record ?: return -1
        val want = minOf(dest.size, CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        return r.read(dest, 0, want)
    }

    override fun stop() {
        val current = record
        record = null
        current?.let { runCatching { it.stopRecording() } }
    }
}

/**
 * Low-latency streaming playback through AudioTrack: the assistant speech attributes by
 * default (the car), voice-call attributes for the phone test with [PhoneCallAudio].
 */
class AudioTrackSink(private val attributes: AudioAttributes = speechAttributes) : PcmSink {
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
                .setAudioAttributes(attributes)
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
        DuplexLog.i("speaker: usage=${attributes.usage} routedType=${t.routedDevice?.type ?: "unknown"}")
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
