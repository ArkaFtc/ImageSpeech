package com.example.test_project.processing.ocr

import com.example.test_project.contract.TextBox

/** Whitespace cuts keep columns together; ordinary rows retain their existing order. */
object ColumnOrder {
    fun <T : TextBox> sort(boxes: List<T>): List<T> {
        if (boxes.size < 4) return ReadingOrder.sort(boxes)
        val height = boxes.map { it.bottom - it.top }.sorted()[boxes.size / 2].coerceAtLeast(1)
        val horizontal = gap(boxes.map { it.left to it.right }, height * 2)
        if (horizontal != null) {
            val left = boxes.filter { it.right <= horizontal }
            val right = boxes.filter { it.left >= horizontal }
            if (left.size >= 2 && right.size >= 2 && left.size + right.size == boxes.size)
                return sort(left) + sort(right)
        }
        val vertical = gap(boxes.map { it.top to it.bottom }, height * 2)
        if (vertical != null) {
            val top = boxes.filter { it.bottom <= vertical }
            val bottom = boxes.filter { it.top >= vertical }
            if (top.isNotEmpty() && bottom.isNotEmpty() && top.size + bottom.size == boxes.size)
                return sort(top) + sort(bottom)
        }
        return ReadingOrder.sort(boxes)
    }

    private fun gap(intervals: List<Pair<Int, Int>>, minimum: Int): Int? {
        val sorted = intervals.sortedBy { it.first }
        var edge = sorted.first().second
        var best = minimum
        var cut: Int? = null
        for ((start, end) in sorted.drop(1)) {
            if (start - edge > best) {
                best = start - edge
                cut = (start + edge) / 2
            }
            edge = maxOf(edge, end)
        }
        return cut
    }
}
