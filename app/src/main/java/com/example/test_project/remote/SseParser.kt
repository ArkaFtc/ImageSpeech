package com.example.test_project.remote

/** Incremental SSE parser. Both network and UTF-8 boundaries may split anywhere. */
class SseParser(private val onData: (String) -> Unit) {
    private val pending = StringBuilder()
    private val eventData = mutableListOf<String>()

    fun feed(text: String) {
        pending.append(text)
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) return
            var line = pending.substring(0, newline)
            pending.delete(0, newline + 1)
            if (line.endsWith('\r')) line = line.dropLast(1)
            acceptLine(line)
        }
    }

    fun finish() {
        if (pending.isNotEmpty()) acceptLine(pending.toString().trimEnd('\r'))
        pending.clear()
        dispatch()
    }

    private fun acceptLine(line: String) {
        if (line.isEmpty()) {
            dispatch()
        } else if (line.startsWith("data:")) {
            eventData += line.removePrefix("data:").trimStart()
        }
    }

    private fun dispatch() {
        if (eventData.isNotEmpty()) {
            onData(eventData.joinToString("\n"))
            eventData.clear()
        }
    }
}
