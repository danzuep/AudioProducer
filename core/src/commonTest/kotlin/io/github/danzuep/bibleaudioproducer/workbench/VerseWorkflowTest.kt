package io.github.danzuep.bibleaudioproducer.workbench

import kotlin.test.Test
import kotlin.test.assertEquals

class VerseWorkflowTest {
    private fun word(surface: String, lemma: String, pos: String, grammaticalCase: String = "") =
        VerseWord(surface, lemma, pos, "", grammaticalCase, "", surface)

    @Test
    fun learnsChunkOrderAndRespectsPerFormOmissions() {
        val words = listOf(
            word("ὁ", "ὁ", "det", "nominative"),
            word("λόγος", "λόγος", "noun", "nominative"),
            word("ἦν", "εἰμί", "verb"),
            word("τὸν", "ὁ", "det", "accusative"),
            word("θεόν", "θεός", "noun", "accusative")
        )
        val chunks = VerseWorkflow.chunks(words)
        assertEquals(listOf("NP.nom", "V", "NP.acc"), chunks.map { it.slot })
        val rules = VerseWorkflow.learnRules(listOf(chunks to listOf(2, 0, 1)))
        assertEquals(listOf(2, 0, 1), VerseWorkflow.order(chunks, rules, null))
        assertEquals(
            listOf("θεόν", "ὁ", "λόγος", "ἦν"),
            VerseWorkflow.spokenWords(words, rules, null, setOf("τὸν")).map { it.surface }
        )
        assertEquals(listOf(2, 0, 1), VerseWorkflow.order(chunks, rules, listOf(0, 0, 2)))
    }

    @Test
    fun ordersCardsByFirstChapterAndFrequency() {
        val cards = listOf(
            RecordingCard("JHN", "b", "", 1),
            RecordingCard("JHN", "a", "", 1),
            RecordingCard("JHN", "c", "", 1)
        )
        val verses = listOf(
            AudioVerse("JHN 1:1", listOf(word("a", "a", "noun"), word("b", "b", "noun"), word("a", "a", "noun"))),
            AudioVerse("JHN 2:1", listOf(word("c", "c", "noun")))
        )
        assertEquals(listOf("a", "b", "c"), VerseWorkflow.chapterOrder(cards, verses).map { it.lemma })
    }

    @Test
    fun explicitVerseOrderReordersIndividualWords() {
        val words = listOf(
            word("ἐν", "ἐν", "prep"),
            word("ἀρχῇ", "ἀρχή", "noun", "dative"),
            word("ἦν", "εἰμί", "verb")
        )
        assertEquals(
            listOf("ἦν", "ἐν", "ἀρχῇ"),
            VerseWorkflow.spokenWords(words, emptyMap(), null, emptySet(), listOf(2, 0, 1)).map { it.surface }
        )
    }
}
