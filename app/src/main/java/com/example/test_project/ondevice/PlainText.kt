package com.example.test_project.ondevice

/**
 * Removes Markdown symbols from Gemma's streamed text, so they are neither shown nor read aloud
 * ("asterisk"). Works on pieces as they arrive: a marker split across two pieces is still caught.
 *
 * Dropped anywhere: `*`, backticks and backslashes. Dropped at the start of a line: `#` headings,
 * `-`, `*` and `•` bullets, and indentation. Everything else, including numbered lists, is kept.
 */
class PlainText {
    private var lineStart = true

    /** Line-start characters that may still turn out to be a marker ("-" or "##"). */
    private val pending = StringBuilder()

    fun offer(piece: String): String {
        val out = StringBuilder(piece.length)
        for (c in piece) {
            if (c in DROPPED) continue
            if (lineStart) {
                when {
                    c == ' ' || c == '\t' -> {
                        // A marker followed by a space was a marker.
                        pending.clear()
                        continue
                    }
                    c == '•' -> continue
                    (c == '#' && pending.all { it == '#' }) || (c == '-' && pending.isEmpty()) -> {
                        pending.append(c)
                        continue
                    }
                    else -> {
                        // Not a marker after all, such as "-5" or "#2".
                        out.append(pending)
                        pending.clear()
                    }
                }
            }
            out.append(c)
            lineStart = c == '\n'
        }
        return out.toString()
    }

    /** Whatever was held back at the end of the text. */
    fun flush(): String = pending.toString().also { pending.clear() }

    companion object {
        private val DROPPED = setOf('*', '`', '\\')

        fun clean(text: String): String = PlainText().let { it.offer(text) + it.flush() }
    }
}
