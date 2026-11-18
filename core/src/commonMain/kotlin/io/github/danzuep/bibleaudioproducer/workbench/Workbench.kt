package io.github.danzuep.bibleaudioproducer.workbench

data class AudioWorkbenchCard(
    val book: String,
    val lemma: String,
    val gloss: String,
    val occurrences: Int
)

data class AudioWorkbenchCoverage(val recorded: Int, val total: Int) {
    val complete: Boolean get() = total > 0 && recorded == total
}

object Workbench {
    fun books(cards: List<AudioWorkbenchCard>): List<String> = cards.map { it.book }.distinct()

    fun coverage(cards: List<AudioWorkbenchCard>, isRecorded: (String) -> Boolean) =
        AudioWorkbenchCoverage(cards.count { isRecorded(it.lemma) }, cards.size)

    fun nextIncompleteBook(cards: List<AudioWorkbenchCard>, isRecorded: (String) -> Boolean): String? =
        books(cards).firstOrNull { book -> cards.any { it.book == book && !isRecorded(it.lemma) } }

    fun search(cards: List<AudioWorkbenchCard>, book: String?, query: String): List<AudioWorkbenchCard> {
        val term = query.trim()
        return cards.filter {
            it.book == book && (term.isEmpty() || it.lemma.contains(term, ignoreCase = true) ||
                it.gloss.contains(term, ignoreCase = true))
        }
    }
}
