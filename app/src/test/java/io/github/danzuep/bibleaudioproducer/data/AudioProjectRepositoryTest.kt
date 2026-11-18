package io.github.danzuep.bibleaudioproducer.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCoverage
import io.github.danzuep.bibleaudioproducer.workbench.RecordingPlan
import io.github.danzuep.bibleaudioproducer.workbench.GrammarRule
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AudioProjectRepositoryTest {
    @Test
    fun parseReadsMaculaCardsAndKeepsOneRecordingPerLemma() {
        val cards = RecordingPlanJson.parse(
            """{"cards":[
                {"book":"JHN","greek":"λογος","gloss":"word","occurrences":330},
                {"book":"JHN","greek":"θεος","gloss":"God","occurrences":132},
                {"book":"MRK","greek":"λογος","gloss":"word","occurrences":1}
            ]}"""
        )

        assertEquals(listOf("λογος", "θεος"), cards.map { it.lemma })
        assertEquals(listOf("JHN"), RecordingPlan.books(cards))
        assertEquals(330, cards.first().occurrences)
    }

    @Test
    fun coverageAndNextBookReflectSavedClips() {
        val cards = RecordingPlanJson.parse(
            """{"cards":[
                {"book":"JHN","greek":"λογος","gloss":"word"},
                {"book":"MRK","greek":"θεος","gloss":"God"}
            ]}"""
        )
        val recorded = setOf("λογος")

        assertEquals(RecordingCoverage(1, 2), RecordingPlan.coverage(cards) { it in recorded })
        assertEquals("MRK", RecordingPlan.nextIncompleteBook(cards) { it in recorded })
        assertTrue(RecordingPlan.coverage(cards) { true }.complete)
        assertFalse(RecordingPlan.coverage(cards) { false }.complete)
    }

    @Test
    fun savedPlanCanBeLoadedAgain() {
        val directory = Files.createTempDirectory("audio-workbench-plan-test").toFile()
        try {
            val repository = AudioProjectRepository(directory)
            val cards = RecordingPlanJson.parse(
                """{"cards":[{"book":"JHN","greek":"λογος","gloss":"word","occurrences":330}]}"""
            )

            repository.savePlan(cards)

            assertEquals(cards, repository.loadPlan())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun loadsPreviouslySavedPlansWithLemmaField() {
        val directory = Files.createTempDirectory("audio-workbench-legacy-plan-test").toFile()
        try {
            File(directory, "recording-plan.json").writeText(
                """{"cards":[{"book":"JHN","lemma":"λογος","gloss":"word","occurrences":330}]}"""
            )
            assertEquals(
                listOf(RecordingCard("JHN", "λογος", "word", 330)),
                AudioProjectRepository(directory).loadPlan()
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exportContainsMaculaCompatibleManifestAndRecordedClips() {
        val directory = Files.createTempDirectory("audio-workbench-test").toFile()
        try {
            val repository = AudioProjectRepository(directory)
            val cards = RecordingPlanJson.parse(
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
            val repository = AudioProjectRepository(directory)
            val cards = (1..1501).map { index ->
                RecordingCard("JHN", "lemma-$index", "word", 1).also { card ->
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

    @Test
    fun importPreservesExistingClipsAndRoundTripsUpstreamMetadata() {
        val directory = Files.createTempDirectory("audio-workbench-import-test").toFile()
        try {
            val repository = AudioProjectRepository(directory)
            val cards = listOf(RecordingCard("JHN", "existing", "", 1))
            repository.clipFile("existing").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(9))
            }
            val archive = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(
                        """{"format":"macula-scripta-audio","version":1,"language":"Example",
                            "rules":{"NP.nom|V":{"keep":0,"flip":1}},
                            "seed_orders":{"JHN 1:1":[1,0]},"unspoken":["τὸν"],
                            "clips":[{"lemma":"other","file":"clips/missing.webm","mime":"audio/webm"},
                                     {"lemma":"existing","file":"clips/1.webm","mime":"audio/webm"},
                                     {"lemma":"other","file":"clips/2.webm","mime":"audio/webm"},
                                     {"lemma":"shared","file":"clips/2.webm","mime":"audio/webm"}]}"""
                            .toByteArray()
                    )
                    zip.closeEntry()
                    for (name in listOf("clips/1.webm", "clips/2.webm")) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(byteArrayOf(1, 2, 3))
                        zip.closeEntry()
                    }
                }
            }

            val result = repository.importArchive(archive.toByteArray().inputStream(), "", AudioProjectState())
            assertEquals(2, result.added)
            assertEquals(2, result.skipped)
            assertEquals("Example", result.language)
            assertEquals(listOf(9), repository.clipFile("existing").readBytes().map { it.toInt() })
            assertEquals(listOf(1, 2, 3), repository.clipFile("shared").readBytes().map { it.toInt() })
            assertEquals(GrammarRule(0, 1), result.state.rules["NP.nom|V"])
            assertEquals(listOf(1, 0), result.state.seedOrders["JHN 1:1"])
            assertEquals(setOf("τὸν"), result.state.unspoken)
            repository.recordingSaved("shared")
            repository.saveState(result.state)
            assertEquals(result.state, repository.loadState())

            val parts = repository.exportParts(cards, result.language)
            val output = ByteArrayOutputStream()
            repository.writeExport(parts.single().cards, result.language, output, state = result.state)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            val manifest = JsonParser.parseString(entries.getValue("manifest.json").toString(Charsets.UTF_8)).asJsonObject
            assertEquals(1, manifest.getAsJsonObject("rules").getAsJsonObject("NP.nom|V").get("flip").asInt)
            assertEquals("audio/webm", manifest.getAsJsonArray("clips")[1].asJsonObject.get("mime").asString)
            assertEquals(listOf(1, 2, 3), entries.getValue("clips/2.webm").map { it.toInt() })
            assertEquals("audio/mp4", manifest.getAsJsonArray("clips")[2].asJsonObject.get("mime").asString)
            assertEquals(listOf(1, 2, 3), entries.getValue("clips/3.m4a").map { it.toInt() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun importRejectsDifferentLanguageBeforeSavingClips() {
        val directory = Files.createTempDirectory("audio-workbench-language-test").toFile()
        try {
            val archive = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write("""{"format":"macula-scripta-audio","version":1,"language":"French","clips":[]}""".toByteArray())
                    zip.closeEntry()
                }

                @Test
                fun recordingCandidateDoesNotReplaceCurrentUntilCommitted() {
                    val directory = Files.createTempDirectory("audio-candidate-test").toFile()
                    try {
                        val repository = AudioProjectRepository(directory)
                        val current = repository.clipFile("λόγος")
                        check(current.parentFile!!.mkdirs())
                        current.writeBytes(byteArrayOf(1))
                        val candidate = repository.recordingCandidateFile("λόγος")
                        candidate.writeBytes(byteArrayOf(2))

                        assertEquals(listOf(1), current.readBytes().map { it.toInt() })
                        repository.commitRecording("λόγος")
                        assertEquals(listOf(2), current.readBytes().map { it.toInt() })
                        assertFalse(candidate.exists())
                        assertEquals(listOf("λόγος"), repository.recordedCards(emptyList()).map { it.lemma })
                        assertTrue(runCatching { repository.commitRecording("λόγος") }.isFailure)
                        assertEquals(listOf(2), current.readBytes().map { it.toInt() })
                    } finally {
                        directory.deleteRecursively()
                    }

                    @Test
                    fun failedRecordingMetadataSaveRestoresBothTakes() {
                        val directory = Files.createTempDirectory("audio-candidate-failure-test").toFile()
                        try {
                            val repository = AudioProjectRepository(directory)
                            val current = repository.clipFile("λόγος")
                            check(current.parentFile!!.mkdirs())
                            current.writeBytes(byteArrayOf(1))
                            val candidate = repository.recordingCandidateFile("λόγος")
                            candidate.writeBytes(byteArrayOf(2))
                            check(File(directory, "clip-metadata.json").mkdir())

                            assertTrue(runCatching { repository.commitRecording("λόγος") }.isFailure)
                            assertEquals(listOf(1), current.readBytes().map { it.toInt() })
                            assertEquals(listOf(2), candidate.readBytes().map { it.toInt() })
                        } finally {
                            directory.deleteRecursively()
                        }
                    }
                }
            }
            val repository = AudioProjectRepository(directory)
            val error = runCatching {
                repository.importArchive(archive.toByteArray().inputStream(), "English", AudioProjectState())
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(repository.recordedCards(emptyList()).isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
