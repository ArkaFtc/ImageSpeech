package com.example.test_project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {

    /** Tokens arrive split at arbitrary points; a word must never reach the voice in halves. */
    @Test
    fun `reassembles sentences across token boundaries`() {
        val chunker = SentenceChunker()

        assertEquals(emptyList<String>(), chunker.offer("The sign "))
        assertEquals(emptyList<String>(), chunker.offer("says clo"))
        assertEquals(emptyList<String>(), chunker.offer("sed."))
        assertEquals(listOf("The sign says closed."), chunker.offer(" It "))

        assertEquals(listOf("It reopens at nine."), chunker.offer("reopens at nine. "))
    }

    /** A terminator at the very end may still turn out to be a decimal point or an abbreviation. */
    @Test
    fun `holds a trailing terminator until the next character arrives`() {
        val chunker = SentenceChunker()

        assertEquals(emptyList<String>(), chunker.offer("It costs 3."))
        assertEquals(emptyList<String>(), chunker.offer("50"))
        assertEquals(listOf("It costs 3.50 total."), chunker.offer(" total. "))
    }

    @Test
    fun `flush returns the unterminated tail exactly once`() {
        val chunker = SentenceChunker()
        chunker.offer("no punctuation here")

        assertEquals("no punctuation here", chunker.flush())
        assertNull(chunker.flush())
    }

    /** OCR output and signage often carry no punctuation at all; it must not buffer forever. */
    @Test
    fun `cuts unpunctuated text at a word boundary once it gets long`() {
        val chunker = SentenceChunker()
        val wall = "word ".repeat(80)

        val pieces = chunker.offer(wall)

        assertEquals(true, pieces.isNotEmpty())
        pieces.forEach { piece ->
            assertEquals("cut mid-word: '$piece'", true, piece.endsWith("word"))
        }
    }

    @Test
    fun `treats a newline as a sentence boundary so verbatim reading breaks per line`() {
        val chunker = SentenceChunker()

        val pieces = chunker.offer("Gate 14\nBoarding 9:40\n")

        assertEquals(listOf("Gate 14", "Boarding 9:40"), pieces)
    }

    @Test
    fun `drops whitespace-only input`() {
        val chunker = SentenceChunker()

        assertEquals(emptyList<String>(), chunker.offer("   \n  "))
        assertNull(chunker.flush())
    }
}
