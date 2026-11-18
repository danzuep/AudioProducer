package io.github.danzuep.bibleaudioproducer.controller.audioworkbench

import com.google.gson.JsonParser
import io.github.danzuep.bibleaudioproducer.workbench.AudioWorkbenchCard

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
}
