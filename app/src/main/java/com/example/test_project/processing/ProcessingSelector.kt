package com.example.test_project.processing

import com.example.test_project.contract.ProcessingAction

enum class ProcessingLane { LOCAL, GEMMA, GEMMA_REQUIRED }

object ProcessingSelector {
    fun select(gemmaReady: Boolean, action: ProcessingAction): ProcessingLane = when {
        gemmaReady -> ProcessingLane.GEMMA
        action == ProcessingAction.ASK || action == ProcessingAction.SUMMARIZE ||
            action == ProcessingAction.SUMMARIZE_DETAILED -> ProcessingLane.GEMMA_REQUIRED
        else -> ProcessingLane.LOCAL
    }
}
