package com.example.test_project.processing.ocr

import com.example.test_project.contract.TextBox
import kotlin.math.max
import kotlin.math.min

/** Orders detected text regions the way a person would read them: top to bottom, left to right */
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
