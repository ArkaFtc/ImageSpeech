package com.example.test_project

import com.example.test_project.ondevice.PlainText
import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test
    fun removesEmphasisCodeAndEscapes() {
        assertEquals("Take two tablets daily.", PlainText.clean("Take **two** tablets `daily`\\."))
    }

    @Test
    fun removesHeadingsAndBulletsAtLineStart() {
        assertEquals("Warnings\nKeep cool.\nKeep dry.\nNext", PlainText.clean("## Warnings\n* Keep cool.\n  - Keep dry.\n• Next"))
    }

    @Test
    fun keepsMeaningfulSymbols() {
        assertEquals("-5 degrees\n#2 pencil\nPart C-3 #4\n1. First", PlainText.clean("-5 degrees\n#2 pencil\nPart C-3 #4\n1. First"))
    }

    @Test
    fun catchesMarkersSplitAcrossPieces() {
        val plain = PlainText()
        val out = listOf("Intro\n#", "# Ti", "tle\n-", " item *", "*x**").joinToString("") { plain.offer(it) } + plain.flush()
        assertEquals("Intro\nTitle\nitem x", out)
    }

    @Test
    fun keepsALoneTrailingDash() {
        assertEquals("a\n-", PlainText.clean("a\n-"))
    }
}
