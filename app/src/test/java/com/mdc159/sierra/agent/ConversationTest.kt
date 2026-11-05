package com.mdc159.sierra.agent

import com.mdc159.sierra.car.ReplyLayout
import com.mdc159.sierra.voice.Pcm
import com.mdc159.sierra.voice.SpeechEndpointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    @Test
    fun keepsSystemPromptAndBoundedHistory() {
        val c = Conversation(maxTurns = 2)
        repeat(5) { c.record("q$it", "a$it") }
        val messages = c.messagesFor("now")
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("one to three short sentences"))
        // system + 2 turns (4 messages) + new user message
        assertEquals(6, messages.size)
        assertEquals("q3", messages[1].content)
        assertEquals("now", messages.last().content)
    }

    @Test
    fun truncatesAtSentenceOrWord() {
        val long = "First sentence is here. Second sentence is a good deal longer than the first one is."
        assertEquals("First sentence is here. …", Conversation.truncateForScreen(long, 40))
        val words = "alpha beta gamma delta epsilon zeta eta theta"
        val cut = Conversation.truncateForScreen(words, 20)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= 20)
        assertEquals("short", Conversation.truncateForScreen("**short**", 40))
    }

    @Test
    fun speechStripsMarkdownAndUrls() {
        assertEquals("Go to link now", Conversation.forSpeech("Go to **https://x.y/z** now"))
    }

    @Test
    fun replyLayoutFitsRows() {
        val reply = "Exit in two miles. The station is on the right. It is open all night. Diesel is cheaper there."
        val rows = ReplyLayout.chunks(reply, maxRows = 2, rowChars = 50)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.length <= 50 })
        assertEquals(listOf("Short."), ReplyLayout.chunks("Short.", 3))
    }

    @Test
    fun wavHeaderIsValid() {
        val wav = Pcm.toWav(ByteArray(320), 16_000)
        assertEquals(44 + 320, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(320, (wav[40].toInt() and 0xFF) or ((wav[41].toInt() and 0xFF) shl 8))
    }

    @Test
    fun rmsOfLoudAndQuietPcm() {
        val loud = ByteArray(64) { if (it % 2 == 0) 0x00 else 0x10 } // 0x1000 = 4096
        assertEquals(4096.0, Pcm.rms(loud, loud.size), 0.1)
        assertEquals(0.0, Pcm.rms(ByteArray(64), 64), 0.0)
    }

    @Test
    fun endpointerStopsAfterSilenceFollowingSpeech() {
        val e = SpeechEndpointer(silenceMs = 300, noSpeechMs = 5_000, maxMs = 10_000)
        repeat(5) { assertFalse(e.offer(100.0, 32)) } // calibrate on cabin noise
        repeat(10) { assertFalse(e.offer(3000.0, 32)) } // speech
        var stopped = false
        repeat(20) { if (e.offer(120.0, 32)) stopped = true }
        assertTrue(e.heardSpeech)
        assertTrue(stopped)
    }

    @Test
    fun endpointerGivesUpWithoutSpeech() {
        val e = SpeechEndpointer(noSpeechMs = 320)
        var stopped = false
        repeat(20) { if (e.offer(100.0, 32)) stopped = true }
        assertFalse(e.heardSpeech)
        assertTrue(stopped)
    }
}
