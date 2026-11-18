package io.github.danzuep.bibleaudioproducer.data

import com.google.gson.JsonParser
import io.github.danzuep.bibleaudioproducer.workbench.AudioVerse
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord

internal object MaculaVerseJson {
    fun parse(json: String): List<AudioVerse> {
        val verses = mutableListOf<AudioVerse>()
        var reference = ""
        var words = mutableListOf<VerseWord>()
        for (element in JsonParser.parseString(json).asJsonArray) {
            val row = element.asJsonArray
            require(row.size() >= 11) { "Invalid Macula verse data." }
            val nextReference = row[0].asString.substringBefore('!')
            if (nextReference != reference) {
                if (reference.isNotEmpty()) verses.add(AudioVerse(reference, words))
                reference = nextReference
                words = mutableListOf()
            }
            words.add(VerseWord(
                surface = row[1].asString, lemma = row[2].asString,
                pos = row[3].asString, type = row[4].asString,
                grammaticalCase = row[7].asString, mood = row[8].asString,
                gloss = row[6].asString, role = row[5].asString,
                mandarin = row[9].asString, morphology = row[10].asString,
                reference = row[0].asString
            ))
        }
        if (reference.isNotEmpty()) verses.add(AudioVerse(reference, words))
        return verses
    }
}
