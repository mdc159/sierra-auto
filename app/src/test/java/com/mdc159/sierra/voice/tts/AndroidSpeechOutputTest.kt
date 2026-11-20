package com.mdc159.sierra.voice.tts

import com.mdc159.sierra.voice.FakeAudioFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSpeechOutputTest {

    private class FakeEngine : TtsEngine {
        lateinit var events: TtsEngine.Listener
        val spoken = mutableListOf<Pair<String, String>>()
        var stops = 0
        var shutdowns = 0
        var refuse = false

        override fun setListener(listener: TtsEngine.Listener) {
            this.events = listener
        }

        override fun speak(text: String, utteranceId: String): Boolean {
            if (refuse) return false
            spoken += text to utteranceId
            return true
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutdowns++
        }
    }

    private val focus = FakeAudioFocus()
    private val engine = FakeEngine()
    private var initCallback: ((Boolean) -> Unit)? = null

    private fun output(initTimeoutMs: Long = 2_000) = AndroidSpeechOutput(focus, { onInit ->
        initCallback = onInit
        engine
    }, initTimeoutMs)

    @Test
    fun waitsForInitThenSpeaksAndReleasesFocusOnDone() = runBlocking {
        val out = output()
        val job = async(Dispatchers.IO) { out.speak("Hello truck.") }
        delay(50)
        assertTrue(engine.spoken.isEmpty())
        initCallback!!(true)
        withTimeout(2_000) { while (engine.spoken.isEmpty()) delay(5) }
        assertEquals("Hello truck.", engine.spoken[0].first)
        assertEquals(listOf(AudioFocusGate.Mode.SPEAK), focus.requests)
        assertTrue(focus.held)
        engine.events.onDone(engine.spoken[0].second)
        assertEquals(SpeakResult.Completed, withTimeout(2_000) { job.await() })
        assertFalse(focus.held)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun stopInterrupts() = runBlocking {
        val out = output()
        initCallback!!(true)
        val job = async(Dispatchers.IO) { out.speak("A long reply.") }
        withTimeout(2_000) { while (engine.spoken.isEmpty()) delay(5) }
        out.stop()
        assertEquals(SpeakResult.Interrupted, withTimeout(2_000) { job.await() })
        assertEquals(1, engine.stops)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun engineErrorAndRefusalAreFailures() = runBlocking {
        val out = output()
        initCallback!!(true)
        val job = async(Dispatchers.IO) { out.speak("Hi.") }
        withTimeout(2_000) { while (engine.spoken.isEmpty()) delay(5) }
        engine.events.onError(engine.spoken[0].second)
        assertTrue(withTimeout(2_000) { job.await() } is SpeakResult.Failed)

        engine.refuse = true
        assertTrue(out.speak("Again.") is SpeakResult.Failed)
        assertEquals(2, focus.abandons)
    }

    @Test
    fun initFailureOrTimeoutIsAFailureNotAHang() = runBlocking {
        val out = output(initTimeoutMs = 100)
        assertEquals(SpeakResult.Failed("Android voice is not available."), out.speak("Hi."))
        val out2 = output()
        initCallback!!(false)
        assertEquals(SpeakResult.Failed("Android voice is not available."), out2.speak("Hi."))
        assertEquals(0, focus.requests.size)
    }

    @Test
    fun deniedFocusDoesNotSpeak() = runBlocking {
        focus.grant = false
        val out = output()
        initCallback!!(true)
        assertEquals(SpeakResult.Failed("Audio is busy. Try again."), out.speak("Hi."))
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun closeShutsTheEngineDown() {
        val out = output()
        out.close()
        assertEquals(1, engine.shutdowns)
    }
}
