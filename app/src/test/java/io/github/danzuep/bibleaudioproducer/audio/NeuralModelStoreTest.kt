package io.github.danzuep.bibleaudioproducer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class NeuralModelStoreTest {
    @Test
    fun rejectsInvalidModelWithoutReplacingTheInstalledModel() {
        val directory = Files.createTempDirectory("neural-model-test").toFile()
        try {
            val store = NeuralModelStore(directory)
            RandomAccessFile(store.modelFile, "rw").use {
                it.setLength(55_432_026)
                it.write(42)
            }
            assertTrue(runCatching { store.install(byteArrayOf(1, 2).inputStream()) }.isFailure)
            assertTrue(store.isInstalled())
            assertEquals(42, store.modelFile.inputStream().use { it.read() })
            assertEquals(listOf(store.modelFile.name), directory.list()!!.toList())
        } finally {
            directory.deleteRecursively()
        }
    }
}
