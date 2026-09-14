package com.example.test_project.settings

/** Keeps every settings input, including QR imports, on the existing key-change path. */
class GeminiKeyChangeFlow(
    private val store: (String) -> Boolean,
    private val cancelUi: () -> Unit,
    private val clearConversation: () -> Unit,
    private val cancelProcessing: () -> Unit,
) {
    fun apply(value: String): Boolean {
        if (!store(value)) return false
        cancelUi()
        clearConversation()
        cancelProcessing()
        return true
    }
}
