package io.github.danzuep.bibleaudioproducer.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import io.github.danzuep.bibleaudioproducer.workbench.AudioArchiveImport
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import io.github.danzuep.bibleaudioproducer.workbench.GrammarRule
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AudioProjectRepository(private val root: File) {
    private val planFile = File(root, "recording-plan.json")
    private val clipsDirectory = File(root, "clips")
    private val metadataFile = File(root, "clip-metadata.json")
    private val stateFile = File(root, "audio-state.json")

    fun loadPlan(): List<RecordingCard> =
        if (planFile.isFile) RecordingPlanJson.parse(planFile.readText()) else emptyList()

    fun savePlan(cards: List<RecordingCard>) {
        require(cards.isNotEmpty()) { "A recording plan must contain at least one card." }
        check(root.mkdirs() || root.isDirectory) { "Unable to create the audio workbench folder." }
        val savedCards = cards.map {
            mapOf("book" to it.book, "greek" to it.lemma, "gloss" to it.gloss, "occurrences" to it.occurrences)
        }
        planFile.writeText(GsonBuilder().create().toJson(mapOf("cards" to savedCards)))
    }

    // A recording of the exact form wins; otherwise the lemma's recording is reused.
    fun playableClip(word: VerseWord): File? =
        clipFile(word.surface).takeIf { it.isFile } ?: clipFile(word.lemma).takeIf { it.isFile }

    fun clipFile(greekForm: String): File {
        val safeName = greekForm.toByteArray(Charsets.UTF_8)
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return File(clipsDirectory, "$safeName.m4a")
    }

    fun recordingCandidateFile(greekForm: String): File {
        val clip = clipFile(greekForm)
        return File(clipsDirectory, "${clip.nameWithoutExtension}.tmp.m4a")
    }

    internal fun newVerseDraft(): File {
        check(root.mkdirs() || root.isDirectory)
        return File.createTempFile("verse-draft-", ".m4a", root)
    }

    internal fun newVersePreview(): File {
        check(root.mkdirs() || root.isDirectory)
        return File.createTempFile("verse-preview-", ".wav", root)
    }

    fun commitRecording(greekForm: String) {
        val candidate = recordingCandidateFile(greekForm)
        require(candidate.isFile && candidate.length() > 0) { "The new recording is unavailable or empty." }
        val current = clipFile(greekForm)
        val backup = if (current.isFile) {
            File.createTempFile("previous-take-", ".tmp", clipsDirectory).also {
                try {
                    current.copyTo(it, overwrite = true)
                } catch (exception: Exception) {
                    it.delete()
                    throw exception
                }
            }
        } else null
        var keepBackup = false
        try {
            check(candidate.renameTo(current)) { "Unable to save the new recording." }
            try {
                recordingSaved(greekForm)
            } catch (exception: Exception) {
                val candidateRestored = current.renameTo(candidate)
                val previousRestored = backup == null || (candidateRestored && backup.renameTo(current))
                if (!candidateRestored || !previousRestored) {
                    keepBackup = backup != null
                    throw IllegalStateException(
                        if (backup != null) "Unable to finish saving; a previous-take backup was retained."
                        else "Unable to finish saving or restore the new recording.",
                        exception
                    )
                }
                throw exception
            }
        } finally {
            if (!keepBackup) backup?.delete()
        }
    }

    fun loadState(): AudioProjectState {
        if (!stateFile.isFile) return AudioProjectState()
        val state = JsonParser.parseString(stateFile.readText()).asJsonObject
        val rules = state.getAsJsonObject("rules")?.entrySet()?.associate { (key, value) ->
            val rule = value.asJsonObject
            key to GrammarRule(rule.get("keep").asInt, rule.get("flip").asInt)
        }.orEmpty()
        val orders = state.getAsJsonObject("seedOrders")?.entrySet()?.associate { (key, value) ->
            key to value.asJsonArray.map { it.asInt }
        }.orEmpty()
        val wordOrders = state.getAsJsonObject("wordOrders")?.entrySet()?.associate { (key, value) ->
            key to value.asJsonArray.map { it.asInt }
        }.orEmpty()
        val unspoken = state.getAsJsonArray("unspoken")?.map { it.asString }?.toSet().orEmpty()
        val neuralOff = state.getAsJsonArray("neuralOff")?.map { it.asString }?.toSet().orEmpty()
        val neuralStrength = state.get("neuralStrength")?.asString
            ?.let { runCatching { io.github.danzuep.bibleaudioproducer.audio.NeuralStrength.valueOf(it) }.getOrNull() }
            ?: io.github.danzuep.bibleaudioproducer.audio.NeuralStrength.STANDARD
        return AudioProjectState(
            rules, orders, unspoken, wordOrders,
            state.get("neuralAudio")?.asBoolean ?: false, neuralOff, neuralStrength
        )
    }

    fun saveState(state: AudioProjectState) = writeJson(stateFile, state)

    fun recordingSaved(greekForm: String) {
        val metadata = loadMetadata().toMutableMap()
        metadata[greekForm] = "audio/mp4"
        writeJson(metadataFile, metadata)
    }

    fun importArchive(input: InputStream, language: String, state: AudioProjectState): AudioArchiveImport {
        check(root.mkdirs() || root.isDirectory) { "Unable to create the audio workbench folder." }
        val scratch = File.createTempFile("audio-import-", ".tmp", root)
        check(scratch.delete() && scratch.mkdir()) { "Unable to prepare audio import." }
        try {
            return MaculaAudioArchive.read(input, scratch, language, state) { clips ->
                check(clipsDirectory.mkdirs() || clipsDirectory.isDirectory)
                val metadata = loadMetadata().toMutableMap()
                val created = mutableListOf<File>()
                try {
                    var skipped = 0
                    for (clip in clips) {
                        val destination = clipFile(clip.surface)
                        if (destination.exists()) {
                            skipped++
                        } else {
                            val staged = File.createTempFile("audio-import-", ".tmp", clipsDirectory)
                            try {
                                clip.file.copyTo(staged, overwrite = true)
                                check(staged.renameTo(destination)) { "Unable to save an imported recording." }
                            } finally {
                                staged.delete()
                            }
                            created.add(destination)
                            metadata[clip.surface] = clip.mime
                        }
                    }
                    writeJson(metadataFile, metadata)
                    created.size to skipped
                } catch (exception: Exception) {
                    created.forEach { it.delete() }
                    throw exception
                }
            }
        } finally {
            scratch.listFiles()?.forEach { it.delete() }
            scratch.delete()
        }
    }

    fun recordedCards(cards: List<RecordingCard>): List<RecordingCard> {
        val known = cards.map { it.lemma }.toSet()
        val extra = loadMetadata().keys.filter { it !in known }
            .map { RecordingCard("", it, "", 0) }
        return (cards + extra).filter { clipFile(it.lemma).isFile }
    }

    private fun loadMetadata(): Map<String, String> =
        if (!metadataFile.isFile) emptyMap() else
            JsonParser.parseString(metadataFile.readText()).asJsonObject.entrySet()
                .associate { it.key to it.value.asString }

    private fun writeJson(file: File, value: Any) {
        check(root.mkdirs() || root.isDirectory) { "Unable to create the audio workbench folder." }
        val temporary = File(root, "${file.name}.tmp")
        temporary.writeText(GsonBuilder().create().toJson(value))
        check(temporary.renameTo(file)) { "Unable to save audio workbench data." }
    }

    fun exportParts(cards: List<RecordingCard>, language: String): List<AudioExportPart> {
        require(language.isNotBlank()) { "Enter a recording language before exporting." }
        val recordedCards = recordedCards(cards)
        require(recordedCards.isNotEmpty()) { "Record at least one clip before exporting." }

        val parts = mutableListOf<AudioExportPart>()
        var current = mutableListOf<RecordingCard>()
        var currentBytes = 0L
        var nextClipNumber = 1
        var firstClipNumber = 1
        recordedCards.forEach { card ->
            val clipBytes = clipFile(card.lemma).length()
            require(clipBytes <= MAX_BYTES_PER_PART) {
                "A recording is too large to include in a MaculaScripta audio archive."
            }
            if (current.isNotEmpty() &&
                (current.size >= MAX_CLIPS_PER_PART || currentBytes + clipBytes > MAX_BYTES_PER_PART)
            ) {
                parts.add(AudioExportPart(current.toList(), firstClipNumber))
                current = mutableListOf()
                currentBytes = 0L
                firstClipNumber = nextClipNumber
            }
            current.add(card)
            currentBytes += clipBytes
            nextClipNumber++
        }
        if (current.isNotEmpty()) parts.add(AudioExportPart(current, firstClipNumber))
        return parts
    }

    fun writeExport(
        cards: List<RecordingCard>,
        language: String,
        output: OutputStream,
        firstClipNumber: Int = 1,
        state: AudioProjectState = AudioProjectState()
    ) {
        require(language.isNotBlank()) { "Enter a recording language before exporting." }
        require(cards.isNotEmpty()) { "Record at least one clip before exporting." }
        val metadata = loadMetadata()
        val clipEntries = cards.mapIndexed { index, card ->
            val mime = metadata[card.lemma] ?: "audio/mp4"
            val extension = when (mime.substringBefore(';')) {
                "audio/webm" -> "webm"
                "audio/ogg" -> "ogg"
                "audio/mp4" -> "m4a"
                "audio/wav" -> "wav"
                "audio/mpeg" -> "mp3"
                else -> "bin"
            }
            ExportClip(card.lemma, "clips/${firstClipNumber + index}.$extension", mime)
        }
        val manifest = ExportManifest(
            language = language.trim(), clips = clipEntries, rules = state.rules,
            seedOrders = state.seedOrders, wordOrders = state.wordOrders,
            unspoken = state.unspoken.toList(), neuralAudio = state.neuralAudio,
            neuralOff = state.neuralOff.toList(), neuralStrength = state.neuralStrength.name
        )
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(GsonBuilder().setPrettyPrinting().create().toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            cards.forEachIndexed { index, card ->
                val clip = clipFile(card.lemma)
                require(clip.length() > 0) { "Clip file is empty and cannot be exported: ${card.lemma}" }
                clip.inputStream().use { input ->
                    zip.putNextEntry(ZipEntry(clipEntries[index].file))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
    }

    companion object {
        private const val MAX_CLIPS_PER_PART = 1500
        private const val MAX_BYTES_PER_PART = 96L * 1024 * 1024
    }
}

data class AudioExportPart(
    val cards: List<RecordingCard>,
    val firstClipNumber: Int
)

private data class ExportManifest(
    val format: String = "macula-scripta-audio",
    val version: Int = 1,
    val language: String,
    val rules: Map<String, GrammarRule> = emptyMap(),
    @SerializedName("seed_orders") val seedOrders: Map<String, List<Int>> = emptyMap(),
    @SerializedName("word_orders") val wordOrders: Map<String, List<Int>> = emptyMap(),
    val unspoken: List<String> = emptyList(),
    @SerializedName("neural_audio") val neuralAudio: Boolean = false,
    @SerializedName("neural_off") val neuralOff: List<String> = emptyList(),
    @SerializedName("neural_strength") val neuralStrength: String = "STANDARD",
    val clips: List<ExportClip>
)

private data class ExportClip(
    val surface: String,
    val file: String,
    val mime: String
)
