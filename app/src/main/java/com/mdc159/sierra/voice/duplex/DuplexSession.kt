package com.mdc159.sierra.voice.duplex

import java.io.IOException

/** Thrown with a short message that can go on a screen. */
class DuplexException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Everything a duplex session reports. Delivered on service threads; keep handlers quick. */
sealed interface DuplexEvent {
    /** The handshake finished; the service is ready for audio. */
    data class Connected(val provider: DuplexProvider) : DuplexEvent

    /** The connection dropped and the session is trying again. */
    data class Reconnecting(val attempt: Int, val reason: String) : DuplexEvent

    /** One chunk of assistant speech, 16-bit little-endian mono PCM at [sampleRate]. */
    class AssistantAudio(val pcm: ByteArray, val sampleRate: Int, val itemId: String?) : DuplexEvent

    /** What the service heard the driver say. [final] is false for a running partial. */
    data class UserTranscript(val text: String, val final: Boolean) : DuplexEvent

    /** What the assistant said, as text. [final] is false for a running partial. */
    data class AssistantTranscript(val text: String, val final: Boolean) : DuplexEvent

    /** The assistant asked for a tool. The session runs it and replies on its own. */
    data class ToolCall(val callId: String, val name: String, val argumentsJson: String) : DuplexEvent

    /** A tool finished. [isError] when the tool threw. */
    data class ToolResult(val callId: String, val name: String, val result: String, val isError: Boolean) : DuplexEvent

    /** The service detected the driver speaking. Cut playback now. */
    data object UserStartedSpeaking : DuplexEvent

    /** The service decided the driver stopped speaking. */
    data object UserStoppedSpeaking : DuplexEvent

    /** The assistant finished a reply. [cancelled] when it was interrupted. */
    data class AssistantDone(val cancelled: Boolean) : DuplexEvent

    /** Something went wrong. [fatal] when the session is over. */
    data class Error(val message: String, val fatal: Boolean) : DuplexEvent

    /** The session ended for good. */
    data class Ended(val reason: String) : DuplexEvent
}

fun interface DuplexListener {
    fun onEvent(event: DuplexEvent)
}

/**
 * One full-duplex voice conversation with a realtime service.
 *
 * Lifecycle: [start] connects and handshakes (blocking, throws [DuplexException]), then
 * [sendAudio] is called continuously with microphone PCM at [inputSampleRate], and events
 * arrive on [DuplexListener] until [close] or [DuplexEvent.Ended].
 */
interface DuplexSession {
    val provider: DuplexProvider

    /** Sample rate the service wants for microphone PCM. Fixed before, or set by, [start]. */
    val inputSampleRate: Int

    /** Sample rate of [DuplexEvent.AssistantAudio] frames. Fixed before, or set by, [start]. */
    val outputSampleRate: Int

    /** Connects and completes the handshake. Blocking; call off the main thread. */
    @Throws(DuplexException::class)
    fun start(listener: DuplexListener)

    /** Feeds 16-bit little-endian mono PCM at [inputSampleRate]. Thread safe. */
    fun sendAudio(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size)

    /**
     * The driver interrupted by tapping (not by speaking). [playedMs] is how much of the
     * current reply has actually come out of the speaker, so the service can forget the rest.
     */
    fun interrupt(playedMs: Long)

    /** Sends a typed message as if spoken, where the service supports it. */
    fun sendText(text: String)

    /** Ends the session. Idempotent. */
    fun close()
}
