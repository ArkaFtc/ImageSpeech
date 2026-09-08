package com.example.test_project

import com.example.test_project.processing.voice.SentenceChunker

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

    /**
     * The other half of the newline rule. In a paragraph the break is only where the column ran
     * out, and cutting there puts a falling full stop in the middle of a clause - which is what
     * made a page of body text sound stuttery.
     */
    @Test
    fun `folds a wrapped line back into its sentence`() {
        val chunker = SentenceChunker()

        val pieces = chunker.offer(
            "Coffee is served from six in the\n" +
                "morning until eleven, and the\n" +
                "kitchen closes at ten. Last orders\n" +
                "are half an hour before that.\n"
        )

        assertEquals(
            listOf(
                "Coffee is served from six in the morning until eleven, " +
                    "and the kitchen closes at ten.",
                "Last orders are half an hour before that.",
            ),
            pieces,
        )
    }

    @Test
    fun `keeps a line that already ended in punctuation as its own utterance`() {
        val chunker = SentenceChunker()

        val pieces = chunker.offer("The door is locked.\nRing the bell for service. ")

        assertEquals(listOf("The door is locked.", "Ring the bell for service."), pieces)
    }

    @Test
    fun `treats a blank line as a paragraph break`() {
        val chunker = SentenceChunker()

        assertEquals(listOf("Chapter one"), chunker.offer("Chapter one\n\nIt was a dark night."))
        assertEquals("It was a dark night.", chunker.flush())
    }

    /** A one-character column is unspeakable on its own; it rides along with the line below. */
    @Test
    fun `merges lines too short to be worth an utterance`() {
        val chunker = SentenceChunker()

        assertEquals(emptyList<String>(), chunker.offer("A\nB\nCancelled"))
        assertEquals("A B Cancelled", chunker.flush())
    }

    @Test
    fun `drops whitespace-only input`() {
        val chunker = SentenceChunker()

        assertEquals(emptyList<String>(), chunker.offer("   \n  "))
        assertNull(chunker.flush())
    }
}
