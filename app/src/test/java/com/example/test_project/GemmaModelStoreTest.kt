package com.example.test_project

import com.example.test_project.ondevice.GemmaModelStore
import com.example.test_project.ondevice.StreamDeltas
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelStoreTest {
    private val directory = Files.createTempDirectory("gemma").toFile().apply { deleteOnExit() }
    private val store = GemmaModelStore(directory, expectedBytes = 10)

    @Test
    fun partialDownloadIsNeverTreatedAsTheModel() {
        store.ensureDirectory()
        store.partialFile.writeBytes(ByteArray(6))

        assertFalse(store.isPresent)
        assertFalse(store.promotePartial())
        assertFalse(store.isPresent)
        assertEquals(6, store.downloadedBytes)
    }

    @Test
    fun exactSizeDownloadBecomesTheModel() {
        store.ensureDirectory()
        store.partialFile.writeBytes(ByteArray(10))

        assertTrue(store.promotePartial())
        assertTrue(store.isPresent)
        assertFalse(store.partialFile.exists())
    }

    @Test
    fun wrongSizeModelFileIsNotPresent() {
        store.ensureDirectory()
        store.modelFile.writeBytes(ByteArray(11))
        assertFalse(store.isPresent)
    }

    @Test
    fun deleteRemovesModelAndPartial() {
        store.ensureDirectory()
        store.modelFile.writeBytes(ByteArray(10))
        store.partialFile.writeBytes(ByteArray(3))

        store.delete()

        assertFalse(store.isPresent)
        assertEquals(0, store.downloadedBytes)
    }

    @Test
    fun streamDeltasAcceptFragments() {
        val deltas = StreamDeltas()
        assertEquals("Hello", deltas.fresh("Hello"))
        assertEquals(" world", deltas.fresh(" world"))
    }

    @Test
    fun streamDeltasAcceptAGrowingFullMessage() {
        val deltas = StreamDeltas()
        assertEquals("Hello", deltas.fresh("Hello"))
        assertEquals(" world", deltas.fresh("Hello world"))
        assertEquals("", deltas.fresh("Hello world"))
    }
}
