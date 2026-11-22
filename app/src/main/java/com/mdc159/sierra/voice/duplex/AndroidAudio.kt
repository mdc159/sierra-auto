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
 * The AudioManager surface [PhoneCallAudio] touches, as an interface so unit tests can
 * inject a failure at a chosen call. The app itself always uses [CallAudioOps.Platform].
 */
interface CallAudioOps {
    var mode: Int

    /** The pre-S speakerphone flag, deprecated from S on. */
    var speakerphoneOn: Boolean
    val communicationDevice: AudioDeviceInfo?
    val availableCommunicationDevices: List<AudioDeviceInfo>

    fun setCommunicationDevice(device: AudioDeviceInfo): Boolean
    fun clearCommunicationDevice()
    fun streamVolume(stream: Int): Int
    fun maxStreamVolume(stream: Int): Int
    fun setStreamVolume(stream: Int, volume: Int, flags: Int)
    fun devicesForAttributes(attributes: AudioAttributes): List<AudioDeviceInfo>

    /** Delegates every call to the platform [AudioManager]. */
    @SuppressLint("NewApi") // every method runs behind PhoneCallAudio's Build.VERSION guards
    class Platform(private val audio: AudioManager) : CallAudioOps {
        override var mode: Int
            get() = audio.mode
            set(value) { audio.mode = value }

        override var speakerphoneOn: Boolean
            get() = @Suppress("DEPRECATION") audio.isSpeakerphoneOn
            set(value) { @Suppress("DEPRECATION") audio.isSpeakerphoneOn = value }

        override val communicationDevice: AudioDeviceInfo? get() = audio.communicationDevice
        override val availableCommunicationDevices: List<AudioDeviceInfo> get() = audio.availableCommunicationDevices
        override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean = audio.setCommunicationDevice(device)
        override fun clearCommunicationDevice() = audio.clearCommunicationDevice()
        override fun streamVolume(stream: Int): Int = audio.getStreamVolume(stream)
        override fun maxStreamVolume(stream: Int): Int = audio.getStreamMaxVolume(stream)
        override fun setStreamVolume(stream: Int, volume: Int, flags: Int) = audio.setStreamVolume(stream, volume, flags)
        override fun devicesForAttributes(attributes: AudioAttributes): List<AudioDeviceInfo> = audio.getAudioDevicesForAttributes(attributes)
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
class PhoneCallAudio(
    context: Context,
    private val ops: CallAudioOps = CallAudioOps.Platform(
        context.applicationContext.getSystemService(AudioManager::class.java),
    ),
) : FocusHandle {
    private val focus = AndroidFocus(context, voiceCallAttributes)
    private var previousMode: Int? = null
    private var routed = false
    /** The route the session replaced: the speakerphone flag before S, the communication device from S on. */
    private var previousSpeakerphone: Boolean? = null
    private var previousCommDevice: AudioDeviceInfo? = null
    private var raisedFrom: Int? = null
    private var raisedTo = 0

    override fun request(onLoss: () -> Unit): Boolean {
        if (!focus.request(onLoss)) return false
        try {
            previousMode = ops.mode
            runCatching { ops.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onFailure { DuplexLog.w("phone audio: communication mode refused ${it.javaClass.simpleName}") }
            routed = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    previousCommDevice = ops.communicationDevice
                    val speaker = ops.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    speaker != null && ops.setCommunicationDevice(speaker)
                } else {
                    // Snapshot before switching: the owner (or another app) may have routed the
                    // speakerphone already, and the session puts back exactly what it found.
                    previousSpeakerphone = ops.speakerphoneOn
                    ops.speakerphoneOn = true
                    true
                }
            }.getOrDefault(false)
            if (routed) awaitLoudspeakerRoute()
            matchAssistantVolume()
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ops.communicationDevice?.type?.toString() ?: "none" else "speakerphone"
            DuplexLog.i(
                "phone audio: mode=${modeName(ops.mode)} (was ${modeName(previousMode ?: -1)}) loudspeaker=$routed commDeviceType=$device " +
                    "voiceCallVolume=${ops.streamVolume(AudioManager.STREAM_VOICE_CALL)}/${ops.maxStreamVolume(AudioManager.STREAM_VOICE_CALL)} " +
                    "musicVolume=${ops.streamVolume(AudioManager.STREAM_MUSIC)}/${ops.maxStreamVolume(AudioManager.STREAM_MUSIC)}",
            )
            return true
        } catch (e: Exception) {
            // Setup failed after the focus was taken and, perhaps, after the mode, route or
            // volume had already been changed: put every one of them back and release the
            // focus, or a failed start leaves the phone in communication mode on speaker.
            DuplexLog.w("phone audio: setup failed (${e.javaClass.simpleName}); restoring")
            undo()
            focus.abandon()
            throw e
        }
    }

    override fun abandon() {
        undo()
        focus.abandon()
    }

    /**
     * Puts back what [request] changed, if anything. The volume, the route and the mode are
     * restored each on its own — one of them failing (a dying audio HAL, say) must not skip
     * the others, or one bad call leaves the phone in communication mode.
     */
    private fun undo() {
        val mode = previousMode
        previousMode = null
        if (mode == null) return
        restoreVolume()
        restoreRoute()
        restoreMode(mode)
    }

    private fun restoreVolume() {
        val from = raisedFrom ?: return
        raisedFrom = null
        runCatching {
            // Put back the owner's call volume, unless they changed it during the session.
            if (ops.streamVolume(AudioManager.STREAM_VOICE_CALL) == raisedTo) {
                ops.setStreamVolume(AudioManager.STREAM_VOICE_CALL, from, 0)
            }
        }.onFailure { DuplexLog.w("phone audio: voice-call volume not restored (${it.javaClass.simpleName})") }
    }

    private fun restoreRoute() {
        val wasRouted = routed
        val priorDevice = previousCommDevice
        val priorSpeakerphone = previousSpeakerphone
        routed = false
        previousCommDevice = null
        previousSpeakerphone = null
        if (!wasRouted) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Back to the device found before the session (another app's routing, say),
                // not to none. That device can be gone by now (the Bluetooth headset hung
                // up mid-session): restoring it then fails or returns false, and clearing
                // is the only way to release the speaker this session claimed.
                val restored = priorDevice != null &&
                    ops.availableCommunicationDevices.any { it.id == priorDevice.id && it.type == priorDevice.type } &&
                    ops.setCommunicationDevice(priorDevice)
                if (!restored) ops.clearCommunicationDevice()
            } else {
                ops.speakerphoneOn = priorSpeakerphone ?: false
            }
        }.onFailure {
            DuplexLog.w("phone audio: route not restored (${it.javaClass.simpleName}); clearing the communication device")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { ops.clearCommunicationDevice() }
        }
    }

    private fun restoreMode(mode: Int) {
        runCatching { ops.mode = mode }
            .onFailure { DuplexLog.w("phone audio: mode not restored (${it.javaClass.simpleName})") }
        DuplexLog.i("phone audio: restored mode=${modeName(mode)}")
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
            // A failure here propagates to request's undo-and-rethrow: an audio service too
            // sick to answer a route query must not leave the session half set up.
            val onSpeaker = ops.devicesForAttributes(voiceCallAttributes).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
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
            val callMax = ops.maxStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val assistant = runCatching { ops.streamVolume(STREAM_ASSISTANT) to ops.maxStreamVolume(STREAM_ASSISTANT) }
                .getOrNull()?.takeIf { it.second > 0 }
                ?: (ops.streamVolume(AudioManager.STREAM_MUSIC) to ops.maxStreamVolume(AudioManager.STREAM_MUSIC))
            val target = Math.round(assistant.first.toDouble() / assistant.second * callMax).toInt().coerceIn(1, callMax)
            val before = ops.streamVolume(AudioManager.STREAM_VOICE_CALL)
            if (before < target) {
                // Remember what to undo before changing it: a failure between the set and the
                // read-back must not leave the raised volume stranded (see undo).
                raisedFrom = before
                raisedTo = target
                ops.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
                runCatching { raisedTo = ops.streamVolume(AudioManager.STREAM_VOICE_CALL) }
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

    override val underrunCount: Int
        get() = track?.let { runCatching { it.underrunCount }.getOrNull() } ?: -1

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
