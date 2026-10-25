package com.example.test_project

import com.example.test_project.contract.ProcessingAction
import com.example.test_project.processing.ProcessingLane
import com.example.test_project.processing.ProcessingSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingSelectorTest {
    @Test fun withoutGemmaReadsLocallyAndQuestionsRequireGemma() {
        assertEquals(ProcessingLane.LOCAL, ProcessingSelector.select(false, ProcessingAction.READ))
        assertEquals(ProcessingLane.LOCAL, ProcessingSelector.select(false, ProcessingAction.READ_ALL))
        assertEquals(ProcessingLane.GEMMA_REQUIRED, ProcessingSelector.select(false, ProcessingAction.ASK))
        assertEquals(ProcessingLane.GEMMA_REQUIRED, ProcessingSelector.select(false, ProcessingAction.SUMMARIZE))
        assertEquals(ProcessingLane.GEMMA_REQUIRED, ProcessingSelector.select(false, ProcessingAction.MORE_DETAIL))
        assertEquals("text boxes are still found silently", ProcessingLane.LOCAL, ProcessingSelector.select(false, ProcessingAction.PREPARE))
    }

    @Test fun downloadedGemmaAlwaysBypassesLocalOcr() {
        ProcessingAction.entries.forEach {
            assertEquals(ProcessingLane.GEMMA, ProcessingSelector.select(true, it))
        }
    }
}
