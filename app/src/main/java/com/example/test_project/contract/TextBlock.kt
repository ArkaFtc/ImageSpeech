package com.example.test_project.contract

/** Anything with an axis-aligned bounding box that reading order can be computed over. */
interface TextBox {
    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
}

/** A run of lines that belong together - a paragraph, a heading, a menu row, an address. */
data class TextBlock(
    val lines: List<String>,
    override val left: Int,
    override val top: Int,
    override val right: Int,
    override val bottom: Int,
    val uncertain: Boolean = false,
    val page: Int = 1,
) : TextBox {

    /** The block as one string, one line per source line. */
    val text: String
        get() = lines.joinToString("\n")

    /** A one-line label for the list, since a long block does not fit a row. */
    val preview: String
        get() = lines.joinToString(" ")
}
