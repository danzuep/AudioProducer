package io.github.danzuep.bibleaudioproducer.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.danzuep.bibleaudioproducer.workbench.GrammarRule
import io.github.danzuep.bibleaudioproducer.workbench.AudioArchiveImport
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

internal data class ImportedClip(val lemma: String, val mime: String, val file: File)

internal object MaculaAudioArchive {
    private const val MAX_ENTRIES = 1600
    private const val MAX_BYTES = 100L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024 * 1024

    fun read(
        input: InputStream,
        scratch: File,
        language: String,
        current: AudioProjectState,
        consume: (List<ImportedClip>) -> Pair<Int, Int>
    ): AudioArchiveImport {
        val entries = mutableMapOf<String, File>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entries.size < MAX_ENTRIES && entry.name !in entries) { "Invalid or oversized audio archive." }
                    val target = File(scratch, entries.size.toString())
                    target.outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BYTES) { "The audio archive is too large." }
                            out.write(buffer, 0, count)
                        }
                    }
                    entries[entry.name] = target
                }
                zip.closeEntry()
            }
        }
        val manifestFile = entries["manifest.json"]
            ?: throw IllegalArgumentException("The audio archive has no manifest.json.")
        require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "The audio manifest is too large." }
        val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject
        require(manifest.string("format") == "macula-scripta-audio" &&
            manifest.get("version")?.asInt == 1) { "This is not a MaculaScripta audio archive." }
        val importedLanguage = manifest.string("language").trim()
        require(language.isBlank() || importedLanguage.isBlank() ||
            language.trim().equals(importedLanguage, ignoreCase = true)) {
            "The archive language differs from the current recording language."
        }
        val clips = manifest.getAsJsonArray("clips")
            ?: throw IllegalArgumentException("The audio archive has no clip list.")
        val imported = mutableListOf<ImportedClip>()
        var skipped = 0
        val seen = mutableSetOf<String>()
        for (element in clips) {
            val clip = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("Invalid audio clip entry.")
            val lemma = clip.string("lemma")
            val filename = clip.string("file")
            val mime = clip.string("mime").ifEmpty { "audio/webm" }
            val file = entries[filename]
            if (lemma.isBlank() || lemma in seen || !mime.startsWith("audio/") ||
                file == null || file.length() == 0L
            ) {
                skipped++
            } else {
                seen.add(lemma)
                imported.add(ImportedClip(lemma, mime, file))
            }
        }
        val incomingRules = readRules(manifest.getAsJsonObject("rules"))
        val incomingOrders = readOrders(manifest.getAsJsonObject("seed_orders"))
        val incomingUnspoken = manifest.getAsJsonArray("unspoken")?.map { it.asString }?.toSet().orEmpty()
        val merged = AudioProjectState(
            rules = if (current.seedOrders.isEmpty()) incomingRules else current.rules,
            seedOrders = if (current.seedOrders.isEmpty()) incomingOrders else current.seedOrders,
            unspoken = current.unspoken + incomingUnspoken
        )
        val (added, alreadyRecorded) = consume(imported)
        return AudioArchiveImport(
            language = language.ifBlank { importedLanguage }, state = merged,
            added = added, skipped = skipped + alreadyRecorded
        )
    }

    private fun readRules(obj: JsonObject?): Map<String, GrammarRule> =
        obj?.entrySet()?.associate { (key, value) ->
            val rule = value.asJsonObject
            val keep = rule.get("keep")?.asInt ?: 0
            val flip = rule.get("flip")?.asInt ?: 0
            require(keep >= 0 && flip >= 0) { "Invalid grammar rule." }
            key to GrammarRule(keep, flip)
        }.orEmpty()

    private fun readOrders(obj: JsonObject?): Map<String, List<Int>> =
        obj?.entrySet()?.associate { (key, value) -> key to value.asJsonArray.map { it.asInt } }.orEmpty()

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
}
