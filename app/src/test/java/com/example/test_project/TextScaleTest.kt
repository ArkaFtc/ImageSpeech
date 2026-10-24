package com.example.test_project

import org.junit.Assert.assertEquals
import org.junit.Test

class TextScaleTest {
    @Test
    fun stepsUpAndDownFromTheDefault() {
        assertEquals(1.15f, TextScale.next(1f, 1))
        assertEquals(0.85f, TextScale.next(1f, -1))
    }

    @Test
    fun staysWithinTheLimits() {
        assertEquals(2f, TextScale.next(2f, 1))
        assertEquals(0.85f, TextScale.next(0.85f, -1))
    }

    @Test
    fun aValueBetweenStepsMovesToTheNeighbouringStep() {
        assertEquals(1f, TextScale.next(1.05f, -1))
        assertEquals(1.3f, TextScale.next(1.05f, 1))
    }

    @Test
    fun reportsWholePercentages() {
        assertEquals(115, TextScale.percent(1.15f))
        assertEquals(200, TextScale.percent(2f))
    }
}
