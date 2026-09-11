package com.example.test_project.processing.ocr

import com.example.test_project.contract.ReadingMode

/** Explicit two-page mode avoids silently bisecting photographs or single landscape pages. */
object PagePlan {
    data class Area(val left: Int, val top: Int, val width: Int, val height: Int)

    fun areas(width: Int, height: Int, mode: ReadingMode, spine: Int = width / 2): List<Area> {
        require(width > 0 && height > 0)
        if (mode != ReadingMode.BOOK_SPREAD || width < 2) return listOf(Area(0, 0, width, height))
        val split = spine.coerceIn(1, width - 1)
        return listOf(Area(0, 0, split, height), Area(split, 0, width - split, height))
    }
}
