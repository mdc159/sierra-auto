package com.mdc159.sierra.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun firstSentenceGoesAloneSoSpeechStartsFast() {
        val text = "Exit in two miles. The station is on the right, just past the overpass. It is open all night."
        val chunks = SentenceChunker.chunks(text, maxChars = 200)
        assertEquals("Exit in two miles.", chunks[0])
        assertEquals("The station is on the right, just past the overpass. It is open all night.", chunks[1])
        assertEquals(2, chunks.size)
    }

    @Test
    fun laterSentencesArePackedUpToMax() {
        val text = "One. Two. Three. Four. Five."
        val chunks = SentenceChunker.chunks(text, maxChars = 11)
        assertEquals(listOf("One.", "Two. Three.", "Four. Five."), chunks)
    }

    @Test
    fun doesNotSplitDecimalsAbbreviationsOrInitials() {
        assertEquals(listOf("Diesel is 3.49 a gallon at Dr. Lee's place, e.g. the Shell on J. Street."),
            SentenceChunker.sentences("Diesel is 3.49 a gallon at Dr. Lee's place, e.g. the Shell on J. Street."))
        assertEquals(listOf("Yes!", "Go now?", "\"Sure.\"", "Then stop."),
            SentenceChunker.sentences("Yes! Go now? \"Sure.\" Then stop."))
    }

    @Test
    fun lineBreaksEndSentences() {
        assertEquals(listOf("Fuel ahead", "Food too"), SentenceChunker.sentences("Fuel ahead\nFood too"))
    }

    @Test
    fun overLongSentenceIsCutAtClauseThenWord() {
        val long = "This is a very long clause that keeps going, and then another clause follows it, and yet another one after that"
        val pieces = SentenceChunker.split(long, 50)
        assertTrue(pieces.all { it.length <= 50 })
        assertTrue(pieces.size >= 3)
        assertEquals(long, pieces.joinToString(" "))
        val words = "a".repeat(30) + " " + "b".repeat(30) + " " + "c".repeat(30)
        assertEquals(listOf("a".repeat(30), "b".repeat(30), "c".repeat(30)), SentenceChunker.split(words, 40))
        assertEquals(listOf("x".repeat(40), "x".repeat(5)), SentenceChunker.split("x".repeat(45), 40))
    }

    @Test
    fun blankTextHasNoChunks() {
        assertEquals(emptyList<String>(), SentenceChunker.chunks("  \n "))
    }
}
