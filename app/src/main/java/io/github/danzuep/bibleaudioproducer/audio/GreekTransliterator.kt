package io.github.danzuep.bibleaudioproducer.audio

import java.text.Normalizer

internal object GreekTransliterator {
    private const val ROUGH = 0x0314
    private const val DIAERESIS = 0x0308
    private const val IOTA_SUBSCRIPT = 0x0345

    fun transliterate(text: String): String {
        val clusters = mutableListOf<Cluster>()
        Normalizer.normalize(text, Normalizer.Form.NFD).forEach { char ->
            if (Character.getType(char) == Character.NON_SPACING_MARK.toInt() && clusters.isNotEmpty()) {
                clusters.last().marks += char.code
            } else {
                clusters += Cluster(char)
            }
        }

        val output = StringBuilder()
        var index = 0
        var wordStart = true
        while (index < clusters.size) {
            val current = clusters[index]
            val base = current.base.lowercaseChar()
            if (base !in GREEK_LETTERS) {
                output.append(current.base)
                current.marks.forEach { output.appendCodePoint(it) }
                wordStart = current.base.isWhitespace() || !current.base.isLetterOrDigit()
                index++
                continue
            }

            val next = clusters.getOrNull(index + 1)
            val pair = next?.takeIf {
                DIAERESIS !in current.marks && DIAERESIS !in it.marks
            }?.let { transliterateDiphthong(base, it.base.lowercaseChar()) }
            val initialRough = wordStart &&
                (current.has(ROUGH) || (pair != null && next?.has(ROUGH) == true))
            val roughRho = wordStart && base == 'ρ' && current.has(ROUGH)
            if (initialRough && !roughRho) {
                output.append(if (current.base.isUpperCase()) 'H' else 'h')
            }
            if (pair != null) {
                output.append(capitalize(pair, current.base.isUpperCase() && !initialRough))
                index += 2
            } else {
                val mapped = when {
                    base == 'γ' && next != null && next.base.lowercaseChar() in VELARS -> "n"
                    base == 'η' -> "ē"
                    base == 'ω' -> "ō"
                    base == 'θ' -> "th"
                    base == 'φ' -> "ph"
                    base == 'χ' -> "ch"
                    base == 'ξ' -> "x"
                    base == 'ψ' -> "ps"
                    base == 'ρ' && wordStart && current.has(ROUGH) -> "rh"
                    else -> SIMPLE[base]?.toString() ?: current.base.toString()
                }
                output.append(
                    capitalize(mapped, current.base.isUpperCase() && (roughRho || !initialRough))
                )
                if (current.has(IOTA_SUBSCRIPT)) output.append('i')
                index++
            }
            wordStart = false
        }
        return output.toString()
    }

    private fun transliterateDiphthong(first: Char, second: Char): String? =
        when ("$first$second") {
            "αι" -> "ai"
            "ει" -> "ei"
            "οι" -> "oi"
            "αυ" -> "au"
            "ευ" -> "eu"
            "ηυ" -> "ēu"
            "ου" -> "ou"
            "υι" -> "ui"
            else -> null
        }

    private fun capitalize(value: String, uppercase: Boolean): String =
        if (uppercase) value.replaceFirstChar { it.uppercase() } else value

    private data class Cluster(val base: Char, val marks: MutableSet<Int> = mutableSetOf()) {
        fun has(mark: Int) = mark in marks
    }

    private val GREEK_LETTERS = "αβγδεζηθικλμνξοπρστυφχψως"
    private val VELARS = "γκχξ"
    private val SIMPLE = mapOf(
        'α' to 'a', 'β' to 'v', 'γ' to 'g', 'δ' to 'd', 'ε' to 'e',
        'ζ' to 'z', 'ι' to 'i', 'κ' to 'k', 'λ' to 'l', 'μ' to 'm',
        'ν' to 'n', 'ο' to 'o', 'π' to 'p', 'ρ' to 'r', 'σ' to 's',
        'ς' to 's', 'τ' to 't', 'υ' to 'y'
    )
}
