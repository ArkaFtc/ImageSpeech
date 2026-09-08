package com.example.test_project.processing.ocr

import com.example.test_project.contract.TextBlock
import com.example.test_project.contract.TextBox
import kotlin.math.max
import kotlin.math.min

/** A recognized line of text: a box that also carries what was read out of it. */
interface TextLine : TextBox {
    val text: String
}

/** Groups OCR lines, already in reading order, into blocks. */
object TextBlocks {

    /**
     * Vertical gap, as a fraction of line height, that still counts as the same block. Leading in
     * body text is a fraction of the line height; a paragraph break is a whole line or more.
     */
    private const val MAX_GAP_RATIO = 0.75

    /** How much two lines must overlap horizontally, as a fraction of the narrower one. */
    private const val MIN_COLUMN_OVERLAP = 0.3

    /** How similar two line heights must be to read as the same run of type. */
    private const val MIN_HEIGHT_RATIO = 0.55

    /** Vertical overlap, as a fraction of the shorter box, that means "the same printed line". */
    private const val SAME_LINE_OVERLAP = 0.5

    fun group(lines: List<TextLine>): List<TextBlock> {
        if (lines.isEmpty()) return emptyList()

        val blocks = mutableListOf<MutableList<TextLine>>()
        for (line in lines) {
            val current = blocks.lastOrNull()
            if (current != null && belongsTogether(current.last(), line)) {
                current.add(line)
            } else {
                blocks.add(mutableListOf(line))
            }
        }
        return blocks.map { block ->
            TextBlock(
                lines = block.map { it.text },
                left = block.minOf { it.left },
                top = block.minOf { it.top },
                right = block.maxOf { it.right },
                bottom = block.maxOf { it.bottom },
            )
        }
    }

    private fun belongsTogether(previous: TextLine, candidate: TextLine): Boolean {
        val previousHeight = previous.bottom - previous.top
        val candidateHeight = candidate.bottom - candidate.top
        if (previousHeight <= 0 || candidateHeight <= 0) return false

        // Side by side on one printed line - a price against its item, a label against its value.
        // Reading order has already put them next to each other, so keeping them in one block is
        // what makes "espresso, three fifty" a single tap.
        val verticalOverlap = min(previous.bottom, candidate.bottom) - max(previous.top, candidate.top)
        if (verticalOverlap > min(previousHeight, candidateHeight) * SAME_LINE_OVERLAP) return true

        val gap = candidate.top - previous.bottom
        if (gap > max(previousHeight, candidateHeight) * MAX_GAP_RATIO) return false

        val heightRatio = min(previousHeight, candidateHeight).toDouble() /
            max(previousHeight, candidateHeight)
        if (heightRatio < MIN_HEIGHT_RATIO) return false

        val horizontalOverlap = min(previous.right, candidate.right) - max(previous.left, candidate.left)
        val narrower = min(previous.right - previous.left, candidate.right - candidate.left)
        if (narrower <= 0) return false
        return horizontalOverlap > narrower * MIN_COLUMN_OVERLAP
    }
}
