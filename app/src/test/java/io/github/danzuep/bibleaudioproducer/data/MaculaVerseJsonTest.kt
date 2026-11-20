package io.github.danzuep.bibleaudioproducer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.danzuep.bibleaudioproducer.workbench.VerseWorkflow
import io.github.danzuep.bibleaudioproducer.workbench.WordOrderMode

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
        assertEquals("", word.syntaxPath)
    }

    @Test
    fun johnSyntaxRespectsClausesAndPredicateRoles() {
        // BSB is an ordering reference, not an exact permutation of Greek tokens:
        // https://github.com/usfm-bible/examples.bsb/blob/50a493b1a6a7828f15833f95920bed81c69fb710/44JHNBSB.usfm
        val json = checkNotNull(javaClass.getResource("/john-syntax.json")).readText()
        val verses = MaculaVerseJson.parse(json).associateBy { it.reference }
        val first = verses.getValue("JHN 1:1").words
        val firstOrder = VerseWorkflow.modeOrder(first, WordOrderMode.ENGLISH_SVO)
        fun before(order: List<Int>, a: Int, b: Int) = order.indexOf(a - 1) < order.indexOf(b - 1)
        // Initial prepositional phrase; final nominative subject, copula, predicate.
        assertTrue(before(firstOrder, 1, 5))
        assertTrue(before(firstOrder, 5, 3))
        assertTrue(before(firstOrder, 17, 15))
        assertTrue(before(firstOrder, 15, 14))
        assertTrue(before(firstOrder, 12, 13))

        val second = verses.getValue("JHN 3:16").words
        val order = VerseWorkflow.modeOrder(second, WordOrderMode.ENGLISH_SVO)
        assertTrue(before(order, 2, 5))
        assertTrue(before(order, 5, 1))
        assertTrue(before(order, 1, 3))
        assertTrue(before(order, 3, 7))
        assertTrue(before(order, 7, 8))
        assertTrue(before(order, 13, 10))
        // The two Greek articles are retained, even though English may need only one.
        assertEquals(listOf(13, 9, 11, 12, 10),
            order.map { it + 1 }.filter { it in 9..13 })
        val greek = VerseWorkflow.modeOrder(second, WordOrderMode.GREEK)
        assertEquals(listOf(9, 10, 11, 12), greek.map { it + 1 }.filter { it in 9..12 })
        assertTrue(before(order, 19, 20))
        assertTrue(before(order, 20, 21))
        assertTrue(before(order, 21, 22))
        assertTrue(before(order, 22, 23))
        assertTrue(before(order, 25, 24))
        for (verse in listOf(first, second)) for (mode in WordOrderMode.entries) {
            assertEquals(verse.indices.toList(), VerseWorkflow.modeOrder(verse, mode).sorted())
        }
        val manual = second.indices.reversed().toList()
        assertEquals(manual, VerseWorkflow.orderedWordIndices(second, emptyMap(), manual))
    }

    @Test
    fun johnTrueLightUsesTheSameArticularAdjectivePattern() {
        // JHN 1:9!2–5, from the pinned MACULA John tree; IDs here identify the same nested groups.
        val verse = MaculaVerseJson.parse(
            """[
              ["JHN 1:9!2","τὸ","ὁ","det","","","the","nominative","","","T-NSN","1,np,s,NP-CL/2,np,,DetNP"],
              ["JHN 1:9!3","φῶς","φῶς","noun","common","","Light","nominative","","光","N-NSN","1,np,s,NP-CL/2,np,,DetNP/3,np,,NPDetAdj"],
              ["JHN 1:9!4","τὸ","ὁ","det","","","-","nominative","","","T-NSN","1,np,s,NP-CL/2,np,,DetNP/3,np,,NPDetAdj/4,np,,DetAdj"],
              ["JHN 1:9!5","ἀληθινὸν","ἀληθινός","adj","","","true","nominative","","真","A-NSN","1,np,s,NP-CL/2,np,,DetNP/3,np,,NPDetAdj/4,np,,DetAdj"]
            ]"""
        ).single()
        assertEquals(listOf(0, 2, 3, 1), VerseWorkflow.modeOrder(verse.words, WordOrderMode.ENGLISH_SVO))
        assertEquals(listOf(0, 1, 2, 3), VerseWorkflow.modeOrder(verse.words, WordOrderMode.GREEK))
    }
}
