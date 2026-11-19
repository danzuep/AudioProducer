package io.github.danzuep.bibleaudioproducer.workbench

import kotlin.test.Test
import kotlin.test.assertEquals

class VerseWorkflowTest {
    private fun word(surface: String, lemma: String, pos: String, grammaticalCase: String = "") =
        VerseWord(surface, lemma, pos, "", grammaticalCase, "", surface)

    @Test
    fun wordsWithoutAnyGlossAreDisabledButStillInOriginalText() {
        val words = listOf(
            word("ὁ", "ὁ", "det", "nominative").copy(gloss = ""),
            word("λόγος", "λόγος", "noun", "nominative"),
            word("τό", "ὁ", "det").copy(gloss = "", mandarin = "这")
        )
        assertEquals(listOf("λόγος", "τό"), VerseWorkflow.spokenWords(words, emptyMap(), null, emptySet()).map { it.surface })
        val restored = mutableSetOf<String>().also { words[0].withDisabled(it, false) }
        assertEquals(false, words[0].disabled(restored))
        words[0].withDisabled(restored, true)
        assertEquals(true, words[0].disabled(restored))
        assertEquals("ὁ λόγος τό", VerseWorkflow.originalText(AudioVerse("John 1:1", words)))
    }

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

    @Test
    fun reordersClauseRolesByWordOrderMode() {
        val words = listOf(
            word("ἐν", "ἐν", "prep"),
            word("ἀρχῇ", "ἀρχή", "noun", "dative"),
            word("ἦν", "εἰμί", "verb"),
            word("ὁ", "ὁ", "det", "nominative"),
            word("λόγος", "λόγος", "noun", "nominative"),
            word("τὸν", "ὁ", "det", "accusative"),
            word("θεόν", "θεός", "noun", "accusative")
        )
        assertEquals(words.indices.toList(), VerseWorkflow.modeOrder(words, WordOrderMode.GREEK))
        assertEquals(listOf(3, 4, 2, 5, 6, 0, 1), VerseWorkflow.modeOrder(words, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(3, 4, 1, 0, 5, 6, 2), VerseWorkflow.modeOrder(words, WordOrderMode.JAPANESE_SOV))
        assertEquals(listOf(2, 3, 4, 5, 6, 0, 1), VerseWorkflow.modeOrder(words, WordOrderMode.ARABIC_VSO))
        assertEquals(listOf(3, 4, 2, 5, 6, 0, 1), VerseWorkflow.modeOrder(words, WordOrderMode.GERMAN_V2))
    }

    @Test
    fun verbSecondFrontsOneConstituentAndHeadFinalMovesGenitives() {
        val objectFirst = listOf(word("ἔχει", "ἔχω", "verb"), word("τὸν", "ὁ", "det", "accusative"), word("θεόν", "θεός", "noun", "accusative"))
        assertEquals(listOf(0, 1, 2), VerseWorkflow.modeOrder(objectFirst, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(1, 2, 0), VerseWorkflow.modeOrder(objectFirst, WordOrderMode.GERMAN_V2))
        val genitive = listOf(
            word("ὁ", "ὁ", "det", "nominative"), word("λόγος", "λόγος", "noun", "nominative"),
            word("τοῦ", "ὁ", "det", "genitive"), word("θεοῦ", "θεός", "noun", "genitive"), word("ἦν", "εἰμί", "verb")
        )
        assertEquals(listOf(0, 1, 2, 3, 4), VerseWorkflow.modeOrder(genitive, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(2, 3, 0, 1, 4), VerseWorkflow.modeOrder(genitive, WordOrderMode.MANDARIN_SADVVO))
    }
}
