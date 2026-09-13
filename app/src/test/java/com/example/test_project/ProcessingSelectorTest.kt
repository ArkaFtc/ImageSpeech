package com.example.test_project

import com.example.test_project.contract.ProcessingAction
import com.example.test_project.processing.ProcessingLane
import com.example.test_project.processing.ProcessingSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingSelectorTest {
    @Test fun emptyKeyReadsLocallyAndQuestionsRequireGemini() {
        assertEquals(ProcessingLane.LOCAL, ProcessingSelector.select(false, ProcessingAction.READ))
        assertEquals(ProcessingLane.LOCAL, ProcessingSelector.select(false, ProcessingAction.READ_ALL))
        assertEquals(ProcessingLane.GEMINI_REQUIRED, ProcessingSelector.select(false, ProcessingAction.ASK))
    }

    @Test fun savedKeyAlwaysBypassesLocalLane() {
        ProcessingAction.entries.forEach {
            assertEquals(ProcessingLane.GEMINI, ProcessingSelector.select(true, it))
        }
    }
}
