package com.example.test_project.processing.voice



class SentenceChunker {

    private val buffer = StringBuilder()

    /** Appends a fragment and returns whatever complete sentences that made available. */
    fun offer(fragment: String): List<String> {
        buffer.append(fragment)
        val ready = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val piece = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (piece.isNotEmpty()) ready.add(piece)
        }
        return ready
    }

    /** Returns the unterminated tail, if any, and clears the buffer. Call once the stream ends. */
    fun flush(): String? {
        val tail = buffer.toString().trim()
        buffer.setLength(0)
        return tail.ifEmpty { null }
    }

    private fun nextCut(): Int? {
        var i = 0
        scan@ while (i < buffer.length) {
            when (buffer[i]) {
                '\n' -> when (classifyNewline(i)) {
                    Newline.BOUNDARY -> return i + 1
                    // A wrapped line rather than a boundary. Fold it to a space in place, so the
                    // rest of the sentence joins on and the scan never reconsiders it.
                    Newline.WRAP -> buffer.setCharAt(i, ' ')
                    // Cannot tell without seeing what follows. Fall through to the soft limit,
                    // which is what stops a pathological line from buffering forever.
                    Newline.UNDECIDED -> break@scan
                }

                // Sentence terminators are not unambiguous either. Mid-stream a trailing '.' may
                // yet turn out to be a decimal point or an abbreviation, so wait for the next
                // character to prove it was the end; flush() catches a genuine final sentence.
                '.', '!', '?' -> {
                    val terminated = i + 1 < buffer.length && buffer[i + 1].isWhitespace()
                    if (terminated && i + 1 >= MIN_SEGMENT) return i + 1
                }
            }
            i++
        }
        if (buffer.length >= SOFT_LIMIT) {
            val boundary = buffer.lastIndexOf(" ", SOFT_LIMIT)
            if (boundary >= MIN_SEGMENT) return boundary + 1
        }
        return null
    }

    /** Decides what the newline at [index] means. */
    private fun classifyNewline(index: Int): Newline {
        // Too little in hand to be worth an utterance of its own - an initial, a stray bullet, a
        // one-character column. It rides along with the line below instead.
        if (index < MIN_SEGMENT) return Newline.WRAP

        val previous = lastVisible(index)
        if (previous != null && previous in TERMINATORS) return Newline.BOUNDARY

        if (index + 1 >= buffer.length) {
            // Nothing after it yet. A short line is a label and can go now; a long one is probably
            // a wrap, and guessing costs more than waiting for the next fragment.
            return if (index <= LABEL_LIMIT) Newline.BOUNDARY else Newline.UNDECIDED
        }

        val next = buffer[index + 1]
        // A blank line is a paragraph break in any layout.
        if (next == '\n') return Newline.BOUNDARY
        // A continuation of the same sentence, still in lower case.
        if (next.isLowerCase()) return Newline.WRAP
        return if (index <= LABEL_LIMIT) Newline.BOUNDARY else Newline.WRAP
    }

    /** The last non-blank character before [index], or null when there is none. */
    private fun lastVisible(index: Int): Char? {
        for (i in index - 1 downTo 0) {
            if (!buffer[i].isWhitespace()) return buffer[i]
        }
        return null
    }

    private enum class Newline { BOUNDARY, WRAP, UNDECIDED }

    private companion object {
        /**
         * Guards against splitting "A." style initials and one-character columns into unspeakable
         * clips. It is also the floor on what a newline may cut loose, so it cannot go far up -
         * "Gate 14" has to survive as an utterance of its own.
         */
        const val MIN_SEGMENT = 6

        /**
         * The longest run of text a newline may still cut on when nothing else says it should.
         * Above this the text reads as a wrapped column rather than a label, and only punctuation
         * or a blank line ends it.
         */
        const val LABEL_LIMIT = 24

        const val SOFT_LIMIT = 220

        val TERMINATORS = charArrayOf('.', '!', '?', ':', ';')
    }
}
