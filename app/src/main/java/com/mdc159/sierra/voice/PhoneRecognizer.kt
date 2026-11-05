package com.mdc159.sierra.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.mdc159.sierra.agent.AgentException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fallback speech-to-text using Android's SpeechRecognizer on the phone, used when no
 * transcription endpoint is configured. Must be called from the main thread.
 */
object PhoneRecognizer {

    suspend fun recognize(context: Context, timeoutMs: Long = 20_000): String {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw AgentException("No speech recognizer on this phone. Set a transcription URL.")
        }
        return try {
            withTimeout(timeoutMs) { listenOnce(context) }
        } catch (e: TimeoutCancellationException) {
            throw AgentException("Listening timed out.")
        }
    }

    private suspend fun listenOnce(context: Context): String = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var finished = false
        fun finish(block: () -> Unit) {
            if (finished) return
            finished = true
            recognizer.destroy()
            block()
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish {
                    if (text.isNullOrBlank()) cont.resumeWithException(AgentException("Didn't catch that."))
                    else cont.resume(text)
                }
            }

            override fun onError(error: Int) {
                finish { cont.resumeWithException(AgentException(describe(error))) }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        cont.invokeOnCancellation {
            main.post {
                if (!finished) {
                    finished = true
                    recognizer.cancel()
                    recognizer.destroy()
                }
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        recognizer.startListening(intent)
    }

    fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service: no connection."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone on your phone."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "Phone microphone error."
        else -> "Speech recognition failed ($error)."
    }
}
