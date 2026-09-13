package com.example.test_project.processing

import com.example.test_project.contract.ProcessingAction

enum class ProcessingLane { LOCAL, GEMINI, GEMINI_REQUIRED }

object ProcessingSelector {
    fun select(hasKey: Boolean, action: ProcessingAction): ProcessingLane = when {
        hasKey -> ProcessingLane.GEMINI
        action == ProcessingAction.ASK -> ProcessingLane.GEMINI_REQUIRED
        else -> ProcessingLane.LOCAL
    }
}
