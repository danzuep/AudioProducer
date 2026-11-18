package io.github.danzuep.bibleaudioproducer.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingPlanTest {
    private val cards = listOf(
        RecordingCard("JHN", "λόγος", "Word", 3),
        RecordingCard("MRK", "θεός", "God", 2)
    )

    @Test
    fun findsTheFirstIncompleteBook() {
        assertEquals("MRK", RecordingPlan.nextIncompleteBook(cards) { it == "λόγος" })
        assertNull(RecordingPlan.nextIncompleteBook(cards) { true })
        assertEquals(RecordingCoverage(1, 2), RecordingPlan.coverage(cards) { it == "λόγος" })
    }

    @Test
    fun searchMatchesOnlyTheSelectedBookIgnoringCase() {
        assertEquals(listOf(cards[0]), RecordingPlan.search(cards, "JHN", " word "))
        assertEquals(emptyList(), RecordingPlan.search(cards, "MRK", "word"))
    }
}
