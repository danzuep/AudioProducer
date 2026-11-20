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
    fun recordingCardsMapToOriginalOccurrencesIncludingDisabledWords() {
        val repeated = word("same", "same", "noun")
        val omitted = word("particle", "particle", "conj").copy(gloss = "")
        val words = listOf(repeated, omitted, repeated, word("verb", "verb", "verb"))
        val cards = VerseWorkflow.recordingWords(words, listOf(3, 2, 1, 0), emptySet())
        assertEquals(listOf(3, 2, 0), cards.map { it.spokenIndex })
        assertEquals(listOf("verb", "same", "same"), cards.map { it.word.surface })
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            VerseWorkflow.recordingWords(words, listOf(3, 2, 0, 0), emptySet())
        }
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
        val incomplete = words.map { it.copy(syntaxPath = it.syntaxPath.replace("2,np,s", "2,!np,s")) }
        assertEquals(words.indices.toList(), VerseWorkflow.modeOrder(incomplete, WordOrderMode.ENGLISH_SVO))
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

    @Test
    fun englishMovesOnlySimplePostpositiveAdjectivesWithinTheirPhrase() {
        val path = "1,cl,,V-O/2,np,o,NpAdjp"
        val noun = word("noun", "noun", "noun").copy(syntaxPath = path)
        val adjective = word("adj", "adj", "adj").copy(syntaxPath = path)
        val words = listOf(noun, adjective)
        assertEquals(listOf(1, 0), VerseWorkflow.modeOrder(words, WordOrderMode.ENGLISH_SVO))
        for (mode in WordOrderMode.entries.filter { it != WordOrderMode.ENGLISH_SVO }) {
            assertEquals(listOf(0, 1), VerseWorkflow.modeOrder(words, mode))
        }
        val withDeterminer = listOf(
            word("det", "det", "det").copy(syntaxPath = "$path/3,np,,DetNP"),
            noun.copy(syntaxPath = "$path/3,np,,DetNP"), adjective
        )
        assertEquals(listOf(0, 2, 1), VerseWorkflow.modeOrder(withDeterminer, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(0, 1), VerseWorkflow.modeOrder(words.map {
            it.copy(syntaxPath = it.syntaxPath.replace("NpAdjp", "unknown"))
        }, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(0, 1), VerseWorkflow.modeOrder(words.map {
            it.copy(syntaxPath = it.syntaxPath.replace("2,np", "2,!np"))
        }, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(0, 1, 2), VerseWorkflow.modeOrder(
            listOf(noun, adjective, adjective), WordOrderMode.ENGLISH_SVO
        ))
        val outside = word("other", "other", "noun").copy(syntaxPath = "1,cl,,V-O/4,np,,")
        assertEquals(listOf(0, 1, 2), VerseWorkflow.modeOrder(
            listOf(noun, outside, adjective), WordOrderMode.ENGLISH_SVO
        ))
        val repeated = listOf(noun, adjective, noun.copy(syntaxPath = "1,cl,,V-O/4,np,,"))
        assertEquals(listOf(1, 0, 2), VerseWorkflow.modeOrder(repeated, WordOrderMode.ENGLISH_SVO))
        val manual = listOf(0, 1)
        assertEquals(manual, VerseWorkflow.orderedWordIndices(words, emptyMap(), manual))
    }

    @Test
    fun onlyAJohnPresetSelectionUpdatesTheProjectDefault() {
        val previous = WordOrderMode.JAPANESE_SOV.name
        assertEquals(WordOrderMode.ENGLISH_SVO.name,
            VerseWorkflow.selectedDefault("JHN 3:16", WordOrderMode.ENGLISH_SVO, previous))
        for (mode in listOf(WordOrderMode.GREEK, WordOrderMode.CUSTOM, null)) {
            assertEquals(previous, VerseWorkflow.selectedDefault("JHN 3:16", mode, previous))
        }
        assertEquals(previous, VerseWorkflow.selectedDefault("JHN 1:1", WordOrderMode.ENGLISH_SVO, previous))
        for (name in listOf(null, "GREEK", "CUSTOM", "obsolete")) {
            assertEquals(null, VerseWorkflow.defaultMode(name))
        }
        assertEquals(WordOrderMode.JAPANESE_SOV, VerseWorkflow.defaultMode(previous))
    }

    @Test
    fun defaultOrderAppliesWithoutReplacingSavedWordOrChunkOrders() {
        val path = "1,cl,,V-O/2,np,o,NpAdjp"
        val words = listOf(
            word("noun", "noun", "noun").copy(syntaxPath = path),
            word("adj", "adj", "adj").copy(syntaxPath = path)
        )
        val verse = AudioVerse("JHN 1:1", words)
        val state = AudioProjectState(defaultWordOrder = "ENGLISH_SVO")
        val order = VerseWorkflow.effectiveWordOrder(verse, state)
        assertEquals(listOf(1, 0), order)
        assertEquals(listOf("adj", "noun"),
            VerseWorkflow.spokenWords(words, emptyMap(), null, emptySet(), order).map { it.surface })
        assertEquals(listOf(0, 1),
            VerseWorkflow.effectiveWordOrder(verse, state.copy(wordOrders = mapOf(verse.reference to listOf(0, 1)))))
        assertEquals(null, VerseWorkflow.effectiveWordOrder(
            verse, state.copy(seedOrders = mapOf(verse.reference to listOf(0)))
        ))
        assertEquals(null, VerseWorkflow.effectiveWordOrder(verse, state.copy(defaultWordOrder = "GREEK")))
    }
}
