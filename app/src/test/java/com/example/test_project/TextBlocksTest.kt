package com.example.test_project

import com.example.test_project.processing.ocr.TextBlocks
import com.example.test_project.processing.ocr.TextLine

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlocksTest {

    private data class Line(
        override val text: String,
        override val left: Int,
        override val top: Int,
        override val right: Int,
        override val bottom: Int,
    ) : TextLine

    private fun blocks(vararg lines: Line) = TextBlocks.group(lines.toList()).map { it.preview }

    /** Body text: tight leading, one margin, one size. It is one thing to listen to. */
    @Test
    fun `keeps a paragraph together`() {
        val result = blocks(
            Line("The quick brown fox jumps", left = 100, top = 200, right = 700, bottom = 240),
            Line("over the lazy dog and then", left = 100, top = 250, right = 700, bottom = 290),
            Line("carries on for a third line.", left = 100, top = 300, right = 660, bottom = 340),
        )

        assertEquals(
            listOf("The quick brown fox jumps over the lazy dog and then carries on for a third line."),
            result,
        )
    }

    /** A blank line between paragraphs is the strongest signal there is, so it must split. */
    @Test
    fun `splits on a paragraph break`() {
        val result = blocks(
            Line("First paragraph line one", left = 100, top = 200, right = 700, bottom = 240),
            Line("first paragraph line two", left = 100, top = 250, right = 700, bottom = 290),
            Line("Second paragraph begins", left = 100, top = 400, right = 700, bottom = 440),
        )

        assertEquals(
            listOf("First paragraph line one first paragraph line two", "Second paragraph begins"),
            result,
        )
    }

    /**
     * A heading sits close to the text under it, so the gap alone will not separate them. The jump
     * in type size is what does - and skipping the heading to get to the body is most of the point
     * of offering blocks at all.
     */
    @Test
    fun `splits a heading from the body beneath it`() {
        val result = blocks(
            Line("CHAPTER ONE", left = 100, top = 100, right = 600, bottom = 190),
            Line("It was a bright cold day", left = 100, top = 210, right = 700, bottom = 250),
            Line("and the clocks were striking", left = 100, top = 260, right = 700, bottom = 300),
        )

        assertEquals(
            listOf("CHAPTER ONE", "It was a bright cold day and the clocks were striking"),
            result,
        )
    }

    /**
     * Two columns of one page. The vertical gap says these belong together - the second line sits
     * one line-height below the first - and only the missing margin says otherwise, which is why
     * the horizontal test has to be there at all.
     */
    @Test
    fun `splits columns that share no margin`() {
        val result = blocks(
            Line("end of the left column", left = 60, top = 200, right = 380, bottom = 240),
            Line("start of the right column", left = 620, top = 250, right = 940, bottom = 290),
        )

        assertEquals(listOf("end of the left column", "start of the right column"), result)
    }

    /**
     * A menu row: the item and its price share a printed line but no margin. This is the case that
     * the column rule gets wrong on its own, and the one where a single tap has to say both halves.
     */
    @Test
    fun `keeps an item and its price on one line together`() {
        val result = blocks(
            Line("Espresso", left = 80, top = 300, right = 300, bottom = 340),
            Line("3.50", left = 700, top = 302, right = 800, bottom = 342),
            Line("Cortado", left = 80, top = 380, right = 300, bottom = 420),
            Line("4.00", left = 700, top = 382, right = 800, bottom = 422),
        )

        // The two rows still separate from each other; only the halves of a row join.
        assertEquals(listOf("Espresso 3.50", "Cortado 4.00"), result)
    }

    @Test
    fun `handles an empty result`() {
        assertEquals(emptyList<String>(), TextBlocks.group(emptyList()))
    }
}
