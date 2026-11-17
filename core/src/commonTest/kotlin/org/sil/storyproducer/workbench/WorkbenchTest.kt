package org.sil.storyproducer.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkbenchTest {
    private val cards = listOf(
        AudioWorkbenchCard("JHN", "λόγος", "Word", 3),
        AudioWorkbenchCard("MRK", "θεός", "God", 2)
    )

    @Test
    fun findsTheFirstIncompleteBook() {
        assertEquals("MRK", Workbench.nextIncompleteBook(cards) { it == "λόγος" })
        assertNull(Workbench.nextIncompleteBook(cards) { true })
        assertEquals(AudioWorkbenchCoverage(1, 2), Workbench.coverage(cards) { it == "λόγος" })
    }

    @Test
    fun searchMatchesOnlyTheSelectedBookIgnoringCase() {
        assertEquals(listOf(cards[0]), Workbench.search(cards, "JHN", " word "))
        assertEquals(emptyList(), Workbench.search(cards, "MRK", "word"))
    }
}
