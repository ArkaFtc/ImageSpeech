package com.example.test_project

import com.example.test_project.contract.ReadingMode
import com.example.test_project.contract.TextBox
import com.example.test_project.processing.ocr.*
import com.example.test_project.processing.voice.streamingSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DocumentReadingTest {
    @Test
    fun `book gutter can be off center without cutting into text`() {
        val profile = DoubleArray(1000) { if (it in 450..490) .1 else 10.0 }
        assertTrue(SpineLocator.locate(profile) in 465..475)
        assertEquals(500, SpineLocator.locate(DoubleArray(1000) { 10.0 }))
        val pages = PagePlan.areas(1000, 800, ReadingMode.BOOK_SPREAD, 470)
        assertEquals(470, pages[1].left)
        assertEquals(1000, pages.sumOf { it.width })
    }

    @Test
    fun `speech begins while recognition is still producing`() = runTest {
        val allowOcrToFinish = CompletableDeferred<Unit>()
        var ocrFinished = false
        val input = flow {
            emit("First sentence.")
            allowOcrToFinish.await()
            emit("Second sentence.")
            ocrFinished = true
        }
        val output =
            streamingSpeech(input) { text ->
                    if (text.startsWith("First")) {
                        assertFalse(ocrFinished)
                        allowOcrToFinish.complete(Unit)
                        delay(100)
                    }
                    text
                }
                .toList()
        assertEquals(listOf("First sentence.", "Second sentence."), output)
    }

    @Test
    fun `stopping speech cancels upstream OCR with bounded lookahead`() = runTest {
        var cancelled = false
        var produced = 0
        val input = flow {
            try {
                repeat(1000) {
                    produced++
                    emit("Passage $it")
                }
            } finally {
                cancelled = true
            }
        }
        streamingSpeech(input) {
                delay(10)
                it
            }
            .take(1)
            .collect()
        assertTrue(cancelled)
        assertTrue("Too much text accumulated: $produced", produced < 20)
    }

    @Test
    fun `book split covers every pixel once including odd widths`() {
        val pages = PagePlan.areas(1101, 708, ReadingMode.BOOK_SPREAD)
        assertEquals(2, pages.size)
        assertEquals(pages[0].width, pages[1].left)
        assertEquals(1101, pages.sumOf { it.width })
        assertEquals(1, PagePlan.areas(1101, 708, ReadingMode.DOCUMENT).size)
    }

    private data class Box(
        val name: String,
        override val left: Int,
        override val top: Int,
        override val right: Int,
        override val bottom: Int,
    ) : TextBox

    @Test
    fun `read whole left column before right column`() {
        val boxes =
            listOf(
                Box("L1", 0, 0, 100, 10),
                Box("R1", 180, 0, 280, 10),
                Box("L2", 0, 15, 100, 25),
                Box("R2", 180, 15, 280, 25),
            )
        assertEquals(listOf("L1", "L2", "R1", "R2"), ColumnOrder.sort(boxes).map { it.name })
    }

    @Test
    fun `uncertain lines are retained instead of silently dropped`() {
        val assembler = PassageAssembler(2, "[Unclear text]")
        val blocks = assembler.offer(LocalPPOCRv6Runner.Region("garbled", 0, 0, 80, 20, .2f))
        assertEquals("[Unclear text]", blocks.single().text)
        assertTrue(blocks.single().uncertain)
        assertEquals(2, blocks.single().page)
    }

    @Test
    fun `first confident line does not wait for complete paragraph`() {
        val assembler = PassageAssembler(1, "[Unclear text]")
        val blocks =
            assembler.offer(
                LocalPPOCRv6Runner.Region("The train arrives at the station", 0, 0, 300, 20)
            )
        assertEquals(1, blocks.size)
        assertFalse(blocks.single().uncertain)
        assertNull(assembler.flush())
    }
}
