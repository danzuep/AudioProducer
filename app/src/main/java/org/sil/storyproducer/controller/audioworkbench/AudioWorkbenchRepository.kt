package org.sil.storyproducer.controller.audioworkbench

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import org.sil.storyproducer.workbench.AudioWorkbenchCard
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AudioWorkbenchRepository(private val root: File) {
    private val planFile = File(root, "recording-plan.json")
    private val clipsDirectory = File(root, "clips")

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

    fun exportParts(cards: List<AudioWorkbenchCard>, language: String): List<AudioWorkbenchExportPart> {
        require(language.isNotBlank()) { "Enter a recording language before exporting." }
        val recordedCards = cards.filter { clipFile(it.lemma).isFile }
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
        firstClipNumber: Int = 1
    ) {
        require(language.isNotBlank()) { "Enter a recording language before exporting." }
        require(cards.isNotEmpty()) { "Record at least one clip before exporting." }
        val clipEntries = cards.mapIndexed { index, card ->
            ExportClip(card.lemma, "clips/${firstClipNumber + index}.m4a", "audio/mp4")
        }
        val manifest = ExportManifest(language = language.trim(), clips = clipEntries)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(GsonBuilder().setPrettyPrinting().create().toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            cards.forEachIndexed { index, card ->
                zip.putNextEntry(ZipEntry("clips/${firstClipNumber + index}.m4a"))
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
    val rules: Map<String, String> = emptyMap(),
    @SerializedName("seed_orders") val seedOrders: Map<String, List<Int>> = emptyMap(),
    val unspoken: List<String> = emptyList(),
    val clips: List<ExportClip>
)

private data class ExportClip(
    val lemma: String,
    val file: String,
    val mime: String
)
