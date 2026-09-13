package com.example.test_project

import com.example.test_project.remote.SseParser
import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {
    @Test fun arbitraryChunkBoundariesAreReassembled() {
        val data = mutableListOf<String>()
        val parser = SseParser(data::add)
        listOf("da", "ta: {\"a\":", "1}\r", "\n\r\n", "data: first\n", "data: second", "\n\n")
            .forEach(parser::feed)
        parser.finish()
        assertEquals(listOf("{\"a\":1}", "first\nsecond"), data)
    }
}
