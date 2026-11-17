package org.sil.storyproducer.controller.audioworkbench

import com.google.gson.JsonParser
import org.sil.storyproducer.workbench.AudioWorkbenchCard
import org.sil.storyproducer.workbench.Workbench

object AudioWorkbenchPlan {
    fun parse(json: String): List<AudioWorkbenchCard> {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (exception: Exception) {
            throw IllegalArgumentException("The selected file is not a valid recording plan.", exception)
        }
        val entries = root.getAsJsonArray("cards")
            ?: throw IllegalArgumentException("The selected file does not contain recording cards.")

        val cards = entries.mapIndexed { index, entry ->
            val card = entry.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("Recording card ${index + 1} is invalid.")
            val book = card.get("book")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val lemma = (card.get("greek") ?: card.get("lemma"))
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (book.isEmpty() || lemma.isEmpty()) {
                throw IllegalArgumentException("Recording card ${index + 1} is missing its book or Greek lemma.")
            }
            AudioWorkbenchCard(
                book = book,
                lemma = lemma,
                gloss = card.get("gloss")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                occurrences = card.get("occurrences")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            )
        }.distinctBy { it.lemma }

        if (cards.isEmpty()) {
            throw IllegalArgumentException("The recording plan contains no cards.")
        }
        return cards
    }

    fun books(cards: List<AudioWorkbenchCard>): List<String> =
        Workbench.books(cards)

    fun coverage(cards: List<AudioWorkbenchCard>, isRecorded: (String) -> Boolean): AudioWorkbenchCoverage =
        Workbench.coverage(cards, isRecorded)

    fun nextIncompleteBook(
        cards: List<AudioWorkbenchCard>,
        isRecorded: (String) -> Boolean
    ): String? =
        Workbench.nextIncompleteBook(cards, isRecorded)
}
