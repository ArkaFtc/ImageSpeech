package com.example.test_project

import com.example.test_project.capture.TextOverlayLayout
import com.example.test_project.contract.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class TextOverlayLayoutTest {
    @Test
    fun `portrait image boxes align inside a landscape fit-center view`() {
        val block = TextBlock(listOf("Label"), 100, 200, 300, 300)
        val bounds = TextOverlayLayout.project(block, 1000, 2000, 1000, 1000, 0)!!
        assertEquals(TextOverlayLayout.Bounds(300, 100, 100, 50), bounds)
    }

    @Test
    fun `coordinates from a downscaled OCR image map back to the original photo`() {
        val block = TextBlock(listOf("Small print"), 500, 250, 1000, 500)
        val bounds = TextOverlayLayout.project(block, 4000, 2000, 1000, 500, 0)!!
        assertEquals(TextOverlayLayout.Bounds(250, 125, 250, 125), bounds)
    }
}
