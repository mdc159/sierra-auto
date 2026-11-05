package com.mdc159.sierra.voice.tts

/** How one call to [SpeechOutput.speak] ended. */
sealed class SpeakResult {
    /** The whole text was spoken. */
    data object Completed : SpeakResult()

    /** [SpeechOutput.stop] was called (barge-in, back button, focus loss) before the end. */
    data object Interrupted : SpeakResult()

    /** Nothing, or only part of the text, could be spoken. [message] is short enough for the car screen. */
    data class Failed(val message: String) : SpeakResult()
}

/**
 * Speaks text through the car speakers.
 *
 * Every implementation takes transient audio focus (USAGE_ASSISTANT, speech) before the
 * first sound and releases it when speech ends, is stopped, or fails, as described in
 * docs/android-auto-constraints.md section 7. Implementations are safe to [stop] from any
 * thread while [speak] is suspended.
 */
interface SpeechOutput {
    /** Human-readable provider name for logs and the settings screen. */
    val name: String

    /**
     * Speaks [text] and suspends until it has been fully spoken, stopped, or failed.
     * Cancelling the calling coroutine also stops playback and releases audio focus.
     */
    suspend fun speak(text: String): SpeakResult

    /** Stops the current utterance at once; a pending [speak] returns [SpeakResult.Interrupted]. */
    fun stop()

    /** Stops and releases engine resources. The instance cannot be reused. */
    fun close()
}
