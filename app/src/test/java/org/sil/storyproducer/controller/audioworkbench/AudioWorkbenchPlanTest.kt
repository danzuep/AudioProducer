package org.sil.storyproducer.controller.audioworkbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class AudioWorkbenchPlanTest {
    @Test
    fun parseReadsMaculaCardsAndKeepsOneRecordingPerLemma() {
        val cards = AudioWorkbenchPlan.parse(
            """{"cards":[
                {"book":"JHN","greek":"λογος","gloss":"word","occurrences":330},
                {"book":"JHN","greek":"θεος","gloss":"God","occurrences":132},
                {"book":"MRK","greek":"λογος","gloss":"word","occurrences":1}
            ]}"""
        )

        assertEquals(listOf("λογος", "θεος"), cards.map { it.lemma })
        assertEquals(listOf("JHN"), AudioWorkbenchPlan.books(cards))
        assertEquals(330, cards.first().occurrences)
    }

    @Test
    fun coverageAndNextBookReflectSavedClips() {
        val cards = AudioWorkbenchPlan.parse(
            """{"cards":[
                {"book":"JHN","greek":"λογος","gloss":"word"},
                {"book":"MRK","greek":"θεος","gloss":"God"}
            ]}"""
        )
        val recorded = setOf("λογος")

        assertEquals(AudioWorkbenchCoverage(1, 2), AudioWorkbenchPlan.coverage(cards) { it in recorded })
        assertEquals("MRK", AudioWorkbenchPlan.nextIncompleteBook(cards) { it in recorded })
        assertTrue(AudioWorkbenchPlan.coverage(cards) { true }.complete)
        assertFalse(AudioWorkbenchPlan.coverage(cards) { false }.complete)
    }

    @Test
    fun savedPlanCanBeLoadedAgain() {
        val directory = Files.createTempDirectory("audio-workbench-plan-test").toFile()
        try {
            val repository = AudioWorkbenchRepository(directory)
            val cards = AudioWorkbenchPlan.parse(
                """{"cards":[{"book":"JHN","greek":"λογος","gloss":"word","occurrences":330}]}"""
            )

            repository.savePlan(cards)

            assertEquals(cards, repository.loadPlan())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exportContainsMaculaCompatibleManifestAndRecordedClips() {
        val directory = Files.createTempDirectory("audio-workbench-test").toFile()
        try {
            val repository = AudioWorkbenchRepository(directory)
            val cards = AudioWorkbenchPlan.parse(
                """{"cards":[{"book":"JHN","greek":"λογος","gloss":"word","occurrences":330}]}"""
            )
            repository.clipFile("λογος").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val output = ByteArrayOutputStream()

            repository.writeExport(cards, "Example", output)

            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            val manifest = entries.getValue("manifest.json").toString(Charsets.UTF_8)

            assertTrue(manifest.contains("macula-scripta-audio"))
            assertTrue(manifest.contains("\"language\": \"Example\""))
            assertTrue(manifest.contains("\"lemma\": \"λογος\""))
            assertEquals(listOf(1, 2, 3), entries.getValue("clips/1.m4a").map { it.toInt() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exportSplitsAfterMaculaClipLimitAndKeepsClipNumbersUnique() {
        val directory = Files.createTempDirectory("audio-workbench-parts-test").toFile()
        try {
            val repository = AudioWorkbenchRepository(directory)
            val cards = (1..1501).map { index ->
                AudioWorkbenchCard("JHN", "lemma-$index", "word", 1).also { card ->
                    repository.clipFile(card.lemma).apply {
                        parentFile?.mkdirs()
                        createNewFile()
                    }
                }
            }

            val parts = repository.exportParts(cards, "Example")

            assertEquals(listOf(1500, 1), parts.map { it.cards.size })
            assertEquals(listOf(1, 1501), parts.map { it.firstClipNumber })
        } finally {
            directory.deleteRecursively()
        }
    }
}
