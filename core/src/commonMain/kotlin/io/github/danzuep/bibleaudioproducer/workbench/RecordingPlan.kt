package io.github.danzuep.bibleaudioproducer.workbench

data class RecordingCard(
    val book: String,
    val lemma: String,
    val gloss: String,
    val occurrences: Int
)

data class RecordingCoverage(val recorded: Int, val total: Int) {
    val complete: Boolean get() = total > 0 && recorded == total
}

object RecordingPlan {
    fun books(cards: List<RecordingCard>): List<String> = cards.map { it.book }.distinct()

    fun coverage(cards: List<RecordingCard>, isRecorded: (String) -> Boolean) =
        RecordingCoverage(cards.count { isRecorded(it.lemma) }, cards.size)

    fun nextIncompleteBook(cards: List<RecordingCard>, isRecorded: (String) -> Boolean): String? =
        books(cards).firstOrNull { book -> cards.any { it.book == book && !isRecorded(it.lemma) } }

    fun search(cards: List<RecordingCard>, book: String?, query: String): List<RecordingCard> {
        val term = query.trim()
        return cards.filter {
            it.book == book && (term.isEmpty() || it.lemma.contains(term, ignoreCase = true) ||
                it.gloss.contains(term, ignoreCase = true))
        }
    }
}
