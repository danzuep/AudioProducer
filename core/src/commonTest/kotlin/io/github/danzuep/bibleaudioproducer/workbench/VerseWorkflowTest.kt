package io.github.danzuep.bibleaudioproducer.workbench

import kotlin.test.Test
import kotlin.test.assertEquals

class VerseWorkflowTest {
    private fun word(surface: String, lemma: String, pos: String, grammaticalCase: String = "") =
        VerseWord(surface, lemma, pos, "", grammaticalCase, "", surface)

    @Test
    fun onboardingStartsJohnThenAdvancesInVerseOrder() {
        val references = listOf("JHN 1:1", "JHN 1:2", "JHN 3:16")
        assertEquals("JHN 1:1", VerseWorkflow.nextReference(references, "JHN 3:16", true))
        assertEquals("JHN 1:2", VerseWorkflow.nextReference(references, "JHN 1:1", false))
        assertEquals(null, VerseWorkflow.nextReference(references, "missing", false))
        assertEquals(null, VerseWorkflow.nextReference(references, "JHN 3:16", false))
    }

    @Test
    fun fullVersePlaybackIncludesProperNamesWithoutChangingLegacyPlayback() {
        val words = listOf(word("Jesus", "Jesus", "proper_noun"), word("said", "say", "verb"))
        assertEquals(listOf("said"), VerseWorkflow.spokenWords(words, emptyMap(), null, emptySet()).map { it.surface })
        assertEquals(words, VerseWorkflow.spokenWords(words, emptyMap(), null, emptySet(), includeProper = true))
    }

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
    fun missingSyntaxKeepsOriginalOrderForEveryMode() {
        val words = listOf(
            word("ἐν", "ἐν", "prep"),
            word("ἀρχῇ", "ἀρχή", "noun", "dative"),
            word("ἦν", "εἰμί", "verb"),
            word("ὁ", "ὁ", "det", "nominative"),
            word("λόγος", "λόγος", "noun", "nominative"),
            word("τὸν", "ὁ", "det", "accusative"),
            word("θεόν", "θεός", "noun", "accusative")
        )
        for (mode in WordOrderMode.entries) {
            assertEquals(words.indices.toList(), VerseWorkflow.modeOrder(words, mode))
        }
    }

    @Test
    fun syntaxRolesDriveEachPresetAndKeepCoordinatedNounPhraseTogether() {
        fun syntax(surface: String, pos: String, role: String, path: String) =
            word(surface, surface, pos).copy(role = role, syntaxPath = path)
        val clause = "1,cl,,V-S-O"
        val words = listOf(
            syntax("v", "verb", "v", clause),
            syntax("s1", "noun", "", "$clause/2,np,s,ConjNp"),
            syntax("and", "conj", "", "$clause/2,np,s,ConjNp"),
            syntax("s2", "noun", "", "$clause/2,np,s,ConjNp"),
            syntax("o", "noun", "o", clause)
        )
        assertEquals(listOf(1, 2, 3, 0, 4), VerseWorkflow.modeOrder(words, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(1, 2, 3, 0, 4), VerseWorkflow.modeOrder(words, WordOrderMode.GERMAN_V2))
        assertEquals(listOf(1, 2, 3, 0, 4), VerseWorkflow.modeOrder(words, WordOrderMode.MANDARIN_SADVVO))
        assertEquals(listOf(1, 2, 3, 4, 0), VerseWorkflow.modeOrder(words, WordOrderMode.JAPANESE_SOV))
        assertEquals(listOf(0, 1, 2, 3, 4), VerseWorkflow.modeOrder(words, WordOrderMode.ARABIC_VSO))
        assertEquals(words.indices.toList(), VerseWorkflow.modeOrder(words, WordOrderMode.GREEK))
        assertEquals(words.indices.toList(), VerseWorkflow.modeOrder(words, WordOrderMode.CUSTOM))
        assertEquals(words.indices.toList(), VerseWorkflow.modeOrder(words.mapIndexed { i, w ->
            if (i == 0) w.copy(syntaxPath = "invalid") else w
        }, WordOrderMode.ENGLISH_SVO))
    }

    @Test
    fun japanesePostpositionsAndGermanSubordinateClausesAreConservative() {
        val cl = "1,cl,,sub-CL/2,cl,,O-V"
        val words = listOf(
            word("object", "object", "noun").copy(role = "o", syntaxPath = cl),
            word("verb", "verb", "verb").copy(role = "v", syntaxPath = cl),
            word("prep", "prep", "prep").copy(syntaxPath = "$cl/3,pp,adv,PrepNp"),
            word("noun", "noun", "noun").copy(syntaxPath = "$cl/3,pp,adv,PrepNp")
        )
        assertEquals(words.indices.toList(), VerseWorkflow.modeOrder(words, WordOrderMode.GERMAN_V2))
        assertEquals(listOf(3, 2, 0, 1), VerseWorkflow.modeOrder(words, WordOrderMode.JAPANESE_SOV))
    }
}
