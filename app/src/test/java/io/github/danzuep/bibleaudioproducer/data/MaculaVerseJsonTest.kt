package io.github.danzuep.bibleaudioproducer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MaculaVerseJsonTest {
    @Test
    fun parsesAllWordMetadataFields() {
        val verse = MaculaVerseJson.parse(
            """[["JHN 1:1!1","Ἐν","ἐν","prep","common","prep","In","genitive","indicative","在","PREP"]]"""
        ).single()
        val word = verse.words.single()

        assertEquals("JHN 1:1", verse.reference)
        assertEquals("Ἐν", word.surface)
        assertEquals("ἐν", word.lemma)
        assertEquals("In", word.gloss)
        assertEquals("prep", word.pos)
        assertEquals("common", word.type)
        assertEquals("prep", word.role)
        assertEquals("genitive", word.grammaticalCase)
        assertEquals("indicative", word.mood)
        assertEquals("在", word.mandarin)
        assertEquals("PREP", word.morphology)
        assertEquals("JHN 1:1!1", word.reference)
    }
}
