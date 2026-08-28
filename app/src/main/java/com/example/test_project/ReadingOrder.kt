package com.example.test_project

import kotlin.math.max
import kotlin.math.min

/** Anything with an axis-aligned bounding box that reading order can be computed over. */
interface TextBox {
    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
}

/**
 * Orders detected text regions the way a person would read them: top to bottom, left to right
 * within a line.
 *
 * The naive version of this sorts by [TextBox.top], which is wrong whenever a page mixes type
 * sizes. A short lowercase word like "and" has a box that starts lower than the box of a large
 * line beneath it, so sorting by top edge swaps the two. Ordering by vertical *centre* is stable
 * against that, because the centre tracks where the line sits rather than how tall its glyphs are.
 *
 * This matters more than it looks: the verbatim lane exists to be faithful, and reading a book
 * cover back in the wrong order is a quiet, plausible-sounding kind of wrong.
 */
object ReadingOrder {

    /**
     * Fraction of the shorter box's height that two boxes must overlap vertically to count as the
     * same line. Half is forgiving enough for a baseline wobble and strict enough that consecutive
     * lines of body text stay separate.
     */
    private const val LINE_OVERLAP = 0.5

    fun <T : TextBox> sort(boxes: List<T>): List<T> {
        if (boxes.size <= 1) return boxes

        val lines = mutableListOf<MutableList<T>>()
        for (box in boxes.sortedBy { it.centerY }) {
            val current = lines.lastOrNull()
            if (current != null && sharesLine(current, box)) {
                current.add(box)
            } else {
                lines.add(mutableListOf(box))
            }
        }
        return lines.flatMap { line -> line.sortedBy { it.left } }
    }

    /** True when [candidate] overlaps the line's vertical band enough to belong to it. */
    private fun sharesLine(line: List<TextBox>, candidate: TextBox): Boolean {
        val lineTop = line.minOf { it.top }
        val lineBottom = line.maxOf { it.bottom }
        val overlap = min(lineBottom, candidate.bottom) - max(lineTop, candidate.top)
        val shorter = min(lineBottom - lineTop, candidate.bottom - candidate.top)
        if (shorter <= 0) return false
        return overlap > shorter * LINE_OVERLAP
    }

    private val TextBox.centerY: Int get() = (top + bottom) / 2
}
