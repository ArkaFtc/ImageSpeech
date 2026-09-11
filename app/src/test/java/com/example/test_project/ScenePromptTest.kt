package com.example.test_project

import com.example.test_project.contract.TextBlock
import com.example.test_project.processing.model.ScenePrompt
import com.example.test_project.processing.ocr.LocalPPOCRv6Runner.OcrResult
import org.junit.Assert.*
import org.junit.Test

class ScenePromptTest {
    @Test
    fun documentContextPreservesPagesAndUncertaintyWithoutCameraCoordinates() {
        val blocks =
            listOf(
                TextBlock(listOf("First page"), 10, 20, 30, 40, page = 1),
                TextBlock(listOf("Unclear word"), 10, 20, 30, 40, uncertain = true, page = 2),
            )
        val result = OcrResult(emptyList(), documentBlocks = blocks)
        val prompt = ScenePrompt.ocrBlock(result)
        assertTrue(result.hasText)
        assertEquals(blocks, result.blocks)
        assertTrue(prompt.contains("Page 1: First page"))
        assertTrue(prompt.contains("Page 2 (uncertain): Unclear word"))
        assertFalse(prompt.contains("[10,20,30,40]"))
        assertFalse(prompt.contains("ground truth"))
    }
}
