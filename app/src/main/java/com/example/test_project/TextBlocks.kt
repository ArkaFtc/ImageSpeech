package com.example.test_project

import kotlin.math.max
import kotlin.math.min

/** A recognized line of text: a box that also carries what was read out of it. */
interface TextLine : TextBox {
    val text: String
}

/**
 * A run of lines that belong together - a paragraph, a heading, a menu row, an address.
 *
 * This is the unit the review screen offers the user. One tap per OCR line would be unusable on a
 * page of body text, and one tap for the whole frame gives no way to skip past the parts that do
 * not matter, so the useful granularity sits between the two.
 */
data class TextBlock(
    val lines: List<String>,
    override val left: Int,
    override val top: Int,
    override val right: Int,
    override val bottom: Int,
) : TextBox {

    /**
     * The block as one string, one line per source line.
     *
     * The newlines are load-bearing: [SentenceChunker] cuts on them, so a block reaches the voice
     * as a series of lines rather than a single utterance that cannot be interrupted cleanly.
     */
    val text: String get() = lines.joinToString("\n")

    /** A one-line label for the list, since a long block does not fit a row. */
    val preview: String get() = lines.joinToString(" ")
}

/**
 * Groups OCR lines, already in reading order, into blocks.
 *
 * The signals are the ones a sighted reader uses without thinking about it: lines that sit close
 * together, start at the same margin and are set in the same size are one thing; a wide gap, a new
 * column or a jump in type size starts another. None of them is reliable alone - a heading is close
 * to the paragraph under it, and a centred line shares no margin with anything - so a line joins the
 * block above it only when all three agree.
 *
 * Getting this wrong is not fatal in either direction: an over-eager split costs an extra tap, and
 * an over-eager join costs a few seconds of listening. It is tuned to split, because skipping ahead
 * is the thing that is hard to do without sight.
 */
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
