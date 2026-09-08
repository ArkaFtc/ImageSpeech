package com.example.test_project

import com.example.test_project.processing.ocr.ReadingOrder
import com.example.test_project.contract.TextBox

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingOrderTest {

    private data class Region(
        val label: String,
        override val left: Int,
        override val top: Int,
        override val right: Int,
        override val bottom: Int,
    ) : TextBox

    private fun order(vararg regions: Region) = ReadingOrder.sort(regions.toList()).map { it.label }

    /** The case that shipped wrong: a book cover mixing type sizes. */
    @Test
    fun `orders a cover that mixes type sizes`() {
        val result = order(
            Region("How I Killed", left = 95, top = 735, right = 610, bottom = 810),
            Region("Pluto", left = 95, top = 820, right = 320, bottom = 895),
            // Short lowercase word: box top sits below the tall line that follows it.
            Region("and", left = 98, top = 925, right = 250, bottom = 965),
            Region("Why It Had It", left = 100, top = 975, right = 660, bottom = 1055),
            Region("Coming", left = 95, top = 1075, right = 440, bottom = 1150),
            Region("Mike Brown", left = 95, top = 1180, right = 410, bottom = 1225),
        )

        assertEquals(
            listOf("How I Killed", "Pluto", "and", "Why It Had It", "Coming", "Mike Brown"),
            result,
        )
    }

    @Test
    fun `reads left to right within a line`() {
        val result = order(
            Region("third", left = 400, top = 100, right = 500, bottom = 140),
            Region("first", left = 100, top = 102, right = 200, bottom = 142),
            Region("second", left = 250, top = 98, right = 350, bottom = 138),
        )

        assertEquals(listOf("first", "second", "third"), result)
    }

    /** Columns must not merge into one line just because they sit at the same height. */
    @Test
    fun `keeps stacked lines separate`() {
        val result = order(
            Region("line two", left = 100, top = 200, right = 400, bottom = 240),
            Region("line one", left = 100, top = 100, right = 400, bottom = 140),
            Region("line three", left = 100, top = 300, right = 400, bottom = 340),
        )

        assertEquals(listOf("line one", "line two", "line three"), result)
    }

    /** A tall heading beside small text on the same row still counts as one line. */
    @Test
    fun `groups differing heights that genuinely share a line`() {
        val result = order(
            Region("small", left = 500, top = 120, right = 600, bottom = 150),
            Region("TALL", left = 100, top = 100, right = 400, bottom = 180),
        )

        assertEquals(listOf("TALL", "small"), result)
    }

    @Test
    fun `handles trivial inputs`() {
        assertEquals(emptyList<String>(), ReadingOrder.sort(emptyList<Region>()).map { it.label })
        assertEquals(listOf("only"), order(Region("only", 0, 0, 10, 10)))
    }
}
