package io.github.danzuep.bibleaudioproducer.controller.audioworkbench

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.github.danzuep.bibleaudioproducer.workbench.AudioWorkbenchCard
import io.github.danzuep.bibleaudioproducer.workbench.GrammarRule
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AudioWorkbenchRepository(private val root: File) {
    private val planFile = File(root, "recording-plan.json")
    private val clipsDirectory = File(root, "clips")
    private val metadataFile = File(root, "clip-metadata.json")
    private val stateFile = File(root, "audio-state.json")

    fun loadPlan(): List<AudioWorkbenchCard> =
        if (planFile.isFile) AudioWorkbenchPlan.parse(planFile.readText()) else emptyList()

    fun savePlan(cards: List<AudioWorkbenchCard>) {
        require(cards.isNotEmpty()) { "A recording plan must contain at least one card." }
        check(root.mkdirs() || root.isDirectory) { "Unable to create the audio workbench folder." }
        val savedCards = cards.map {
            mapOf("book" to it.book, "greek" to it.lemma, "gloss" to it.gloss, "occurrences" to it.occurrences)
        }
        planFile.writeText(GsonBuilder().create().toJson(mapOf("cards" to savedCards)))
    }

    fun clipFile(lemma: String): File {
        val safeName = lemma.toByteArray(Charsets.UTF_8)
            .let { java.security.MessageDigest.getInstance("SHA-256").digest(it) }
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return File(clipsDirectory, "$safeName.m4a")
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
        val unspoken = state.getAsJsonArray("unspoken")?.map { it.asString }?.toSet().orEmpty()
        return AudioProjectState(rules, orders, unspoken)
    }

    fun saveState(state: AudioProjectState) = writeJson(stateFile, state)

    fun recordingSaved(lemma: String) {
        val metadata = loadMetadata().toMutableMap()
        metadata[lemma] = "audio/mp4"
        writeJson(metadataFile, metadata)
    }

    fun importArchive(input: InputStream, language: String, state: AudioProjectState): AudioArchiveImport {
        check(root.mkdirs() || root.isDirectory) { "Unable to create the audio workbench folder." }
        val scratch = File.createTempFile("audio-import-", ".tmp", root)
        check(scratch.delete() && scratch.mkdir()) { "Unable to prepare audio import." }
        try {
            return MaculaArchive.read(input, scratch, language, state) { clips ->
                check(clipsDirectory.mkdirs() || clipsDirectory.isDirectory)
                val metadata = loadMetadata().toMutableMap()
                val created = mutableListOf<File>()
                try {
                    var skipped = 0
                    for (clip in clips) {
                        val destination = clipFile(clip.lemma)
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
                            metadata[clip.lemma] = clip.mime
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

    fun recordedCards(cards: List<AudioWorkbenchCard>): List<AudioWorkbenchCard> {
        val known = cards.map { it.lemma }.toSet()
        val extra = loadMetadata().keys.filter { it !in known }
            .map { AudioWorkbenchCard("", it, "", 0) }
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

    fun exportParts(cards: List<AudioWorkbenchCard>, language: String): List<AudioWorkbenchExportPart> {
        require(language.isNotBlank()) { "Enter a recording language before exporting." }
        val recordedCards = recordedCards(cards)
        require(recordedCards.isNotEmpty()) { "Record at least one clip before exporting." }

        val parts = mutableListOf<AudioWorkbenchExportPart>()
        var current = mutableListOf<AudioWorkbenchCard>()
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
                parts.add(AudioWorkbenchExportPart(current.toList(), firstClipNumber))
                current = mutableListOf()
                currentBytes = 0L
                firstClipNumber = nextClipNumber
            }
            current.add(card)
            currentBytes += clipBytes
            nextClipNumber++
        }
        if (current.isNotEmpty()) parts.add(AudioWorkbenchExportPart(current, firstClipNumber))
        return parts
    }

    fun writeExport(
        cards: List<AudioWorkbenchCard>,
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
            seedOrders = state.seedOrders, unspoken = state.unspoken.toList()
        )
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(GsonBuilder().setPrettyPrinting().create().toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            cards.forEachIndexed { index, card ->
                zip.putNextEntry(ZipEntry(clipEntries[index].file))
                val clip = clipFile(card.lemma)
                require(clip.length() > 0) { "Clip file is empty and cannot be exported: ${card.lemma}" }
                clip.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val MAX_CLIPS_PER_PART = 1500
        private const val MAX_BYTES_PER_PART = 96L * 1024 * 1024
    }
}

data class AudioWorkbenchExportPart(
    val cards: List<AudioWorkbenchCard>,
    val firstClipNumber: Int
)

private data class ExportManifest(
    val format: String = "macula-scripta-audio",
    val version: Int = 1,
    val language: String,
    val rules: Map<String, GrammarRule> = emptyMap(),
    @SerializedName("seed_orders") val seedOrders: Map<String, List<Int>> = emptyMap(),
    val unspoken: List<String> = emptyList(),
    val clips: List<ExportClip>
)

private data class ExportClip(
    val lemma: String,
    val file: String,
    val mime: String
)
