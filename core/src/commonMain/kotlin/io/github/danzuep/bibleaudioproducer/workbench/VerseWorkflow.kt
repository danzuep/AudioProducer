package io.github.danzuep.bibleaudioproducer.workbench

data class VerseWord(
    val surface: String,
    val lemma: String,
    val pos: String,
    val type: String,
    val grammaticalCase: String,
    val mood: String,
    val gloss: String,
    val role: String = "",
    val mandarin: String = "",
    val morphology: String = "",
    val reference: String = "",
    val syntaxPath: String = "",
    val id: String = "",
    val head: Boolean = false,
    val sourceGloss: String = "",
    val sourceText: String = ""
) {
    val proper: Boolean get() = type == "proper" || pos == "proper_noun"
    val unspokenKey: String get() = surface.ifEmpty { lemma }
    val glossless: Boolean get() = gloss.isBlank() && mandarin.isBlank()

    // Glossless words are disabled unless explicitly restored, which is recorded as a "+" marked key.
    fun disabled(unspoken: Set<String>) = when {
        unspokenKey in unspoken -> true
        "+$unspokenKey" in unspoken -> false
        else -> glossless
    }

    fun withDisabled(unspoken: MutableSet<String>, disabled: Boolean) {
        unspoken.remove(unspokenKey)
        unspoken.remove("+$unspokenKey")
        if (disabled) unspoken.add(unspokenKey) else if (glossless) unspoken.add("+$unspokenKey")
    }
}

data class AudioVerse(val reference: String, val words: List<VerseWord>, val syntax: SyntaxGroup? = null) {
    val chapter: Int get() = reference.substringAfter(' ').substringBefore(':').toInt()
}

data class VerseRecordingWord(val word: VerseWord, val originalIndex: Int)

data class GrammarChunk(val slot: String, val words: List<VerseWord>)
data class GrammarRule(val keep: Int = 0, val flip: Int = 0)

enum class WordOrderMode(
    val slots: List<String>?,
    val headFinal: Boolean = false,
    val postpositions: Boolean = false,
    val verbSecond: Boolean = false
) {
    GREEK(null),
    ENGLISH_SVO(listOf("CONJ", "S", "V", "IO", "O", "ADV")),
    GERMAN_V2(listOf("CONJ", "S", "V", "IO", "O", "ADV"), verbSecond = true),
    MANDARIN_SADVVO(listOf("CONJ", "S", "ADV", "V", "IO", "O"), headFinal = true),
    JAPANESE_SOV(listOf("S", "ADV", "IO", "O", "V", "CONJ"), headFinal = true, postpositions = true),
    ARABIC_VSO(listOf("CONJ", "V", "S", "O", "IO", "ADV")),
    CUSTOM(null)
}

object VerseWorkflow {
    /** Keep display order while retaining each card's position in the complete source verse. */
    fun recordingWords(words: List<VerseWord>, order: List<Int>, unspoken: Set<String>): List<VerseRecordingWord> {
        require(validOrder(order, words.size)) { "Invalid verse word order." }
        return order.filter { !words[it].disabled(unspoken) }.map { VerseRecordingWord(words[it], it) }
    }

    fun defaultMode(name: String?): WordOrderMode? =
        WordOrderMode.entries.firstOrNull { it.name == name && it != WordOrderMode.GREEK && it != WordOrderMode.CUSTOM }

    fun selectedDefault(reference: String, mode: WordOrderMode?, previous: String?): String? =
        if (reference == "JHN 3:16" && mode != null && defaultMode(mode.name) != null) mode.name else previous

    fun effectiveWordOrder(verse: AudioVerse, state: AudioProjectState): List<Int>? =
        state.wordOrders[verse.reference] ?: if (verse.reference in state.seedOrders) null
        else defaultMode(state.defaultWordOrder)?.let { modeOrder(verse, it) }

    private val nominal = setOf("noun", "pron", "det", "adj", "num")
    private val heads = setOf("noun", "pron", "num")
    private val cases = mapOf(
        "nominative" to "nom", "accusative" to "acc", "dative" to "dat",
        "genitive" to "gen", "vocative" to "voc"
    )

    fun nextReference(references: List<String>, current: String, onboarding: Boolean): String? {
        if (onboarding && current == "JHN 3:16") return references.firstOrNull { it == "JHN 1:1" }
        val index = references.indexOf(current)
        return if (index >= 0) references.getOrNull(index + 1) else null
    }

    fun chapterOrder(cards: List<RecordingCard>, verses: List<AudioVerse>): List<RecordingCard> {
        val byLemma = cards.associateBy { it.lemma }
        val counts = mutableMapOf<Int, MutableMap<String, Int>>()
        for (verse in verses) {
            val chapter = counts.getOrPut(verse.chapter) { linkedMapOf() }
            for (word in verse.words) {
                if (word.lemma in byLemma) chapter[word.lemma] = (chapter[word.lemma] ?: 0) + 1
            }
        }
        val placed = mutableSetOf<String>()
        val ordered = mutableListOf<RecordingCard>()
        for ((_, chapter) in counts.entries.sortedBy { it.key }) {
            for ((lemma, _) in chapter.entries.sortedByDescending { it.value }) {
                if (placed.add(lemma)) ordered.add(byLemma.getValue(lemma))
            }
        }
        return ordered + cards.filter { it.lemma !in placed }
    }

    fun chunks(words: List<VerseWord>): List<GrammarChunk> {
        data class Chunk(
            var slot: String,
            val words: MutableList<VerseWord>,
            var headed: Boolean,
            var grammaticalCase: String? = null
        )
        val chunks = mutableListOf<Chunk>()
        var open: Chunk? = null
        var last: Chunk? = null
        fun nounSlot(word: VerseWord) = "NP.${cases[word.grammaticalCase] ?: "x"}"
        fun add(slot: String, word: VerseWord, headed: Boolean): Chunk =
            Chunk(slot, mutableListOf(word), headed).also { chunks.add(it) }

        for (word in words) {
            when {
                word.pos == "verb" -> {
                    open = null
                    last = null
                    add(when (word.mood) {
                        "participle" -> "PTC"
                        "infinitive" -> "INF"
                        else -> "V"
                    }, word, true)
                }
                word.pos == "prep" -> {
                    last = null
                    open = add("PP", word, false)
                }
                word.pos in nominal -> {
                    val current = open
                    if (current != null && !current.headed) {
                        current.words.add(word)
                        if (word.pos in heads) {
                            current.headed = true
                            if (current.slot != "PP") current.slot = nounSlot(word)
                            last = current
                        }
                    } else if (word.pos == "adj" && last?.headed == true &&
                        word.grammaticalCase == last.grammaticalCase
                    ) {
                        last.words.add(word)
                    } else {
                        val next = add(nounSlot(word), word, word.pos in heads)
                        next.grammaticalCase = word.grammaticalCase
                        last = next
                        open = if (next.headed) null else next
                    }
                }
                else -> {
                    open = null
                    last = null
                    add(when (word.pos) {
                        "conj" -> "CONJ"
                        "adv" -> "ADV"
                        else -> "OTHER"
                    }, word, true)
                }
            }
        }
        return chunks.map { GrammarChunk(it.slot, it.words.toList()) }
    }

    fun learnRules(examples: List<Pair<List<GrammarChunk>, List<Int>>>): Map<String, GrammarRule> {
        val rules = linkedMapOf<String, GrammarRule>()
        for ((chunks, order) in examples) {
            require(validOrder(order, chunks.size))
            val clauses = clauses(chunks)
            val positions = order.withIndex().associate { it.value to it.index }
            for (i in chunks.indices) for (j in i + 1 until chunks.size) {
                if (clauses[i] != clauses[j]) continue
                val key = "${chunks[i].slot}|${chunks[j].slot}"
                val rule = rules[key] ?: GrammarRule()
                rules[key] = if (positions.getValue(i) < positions.getValue(j)) {
                    rule.copy(keep = rule.keep + 1)
                } else {
                    rule.copy(flip = rule.flip + 1)
                }
            }
        }
        return rules
    }

    fun order(chunks: List<GrammarChunk>, rules: Map<String, GrammarRule>, explicit: List<Int>?): List<Int> {
        if (explicit != null && validOrder(explicit, chunks.size)) return explicit
        val clauses = clauses(chunks)
        return clauses.distinct().flatMap { clause ->
            val members = chunks.indices.filter { clauses[it] == clause }
            fun keeps(i: Int, j: Int): Double {
                val rule = rules["${chunks[i].slot}|${chunks[j].slot}"] ?: return 1.0
                val total = rule.keep + rule.flip
                return if (total > 0) rule.keep.toDouble() / total else 1.0
            }
            fun score(i: Int) = members.sumOf { j ->
                when {
                    i == j -> 0.0
                    i < j -> keeps(i, j)
                    else -> 1.0 - keeps(j, i)
                }
            }
            members.sortedWith(compareByDescending<Int> { score(it) }.thenBy { it })
        }
    }

    fun spokenWords(
        words: List<VerseWord>, rules: Map<String, GrammarRule>,
        explicit: List<Int>?, unspoken: Set<String>, wordOrder: List<Int>? = null,
        includeProper: Boolean = false
    ): List<VerseWord> = orderedWordIndices(words, rules, wordOrder, explicit).map(words::get)
        .filter { (includeProper || !it.proper) && !it.disabled(unspoken) }

    fun originalText(verse: AudioVerse) = verse.words.joinToString(" ") { it.surface }

    fun orderedWordIndices(
        words: List<VerseWord>, rules: Map<String, GrammarRule>,
        wordOrder: List<Int>?, chunkOrder: List<Int>? = null
    ): List<Int> {
        if (wordOrder != null && validOrder(wordOrder, words.size)) return wordOrder
        val chunks = chunks(words)
        val remaining = words.indices.toMutableList()
        val chunkWordIndices = chunks.map { chunk ->
            chunk.words.map { word ->
                remaining.first { words[it] == word }.also { remaining.remove(it) }
            }
        }
        return order(chunks, rules, chunkOrder).flatMap { chunkWordIndices[it] }
    }

    fun modeOrder(verse: AudioVerse, mode: WordOrderMode): List<Int> =
        verse.syntax?.let { MaculaWordOrder.order(verse.words, it, mode) } ?: modeOrder(verse.words, mode)

    fun reorder(verse: AudioVerse, mode: WordOrderMode): List<VerseWord> =
        modeOrder(verse, mode).map(verse.words::get)

    fun modeOrder(words: List<VerseWord>, mode: WordOrderMode): List<Int> {
        val original = words.indices.toList()
        val slots = mode.slots ?: return original
        if (words.any { it.syntaxPath.isBlank() }) return original

        data class Node(
            val id: String, val category: String, val role: String, val rule: String,
            val children: MutableList<Node> = mutableListOf(), var word: Int? = null
        )
        val root = Node("", "", "", "")
        val nodes = mutableMapOf<String, Node>()
        for ((index, word) in words.withIndex()) {
            var parent = root
            for (part in word.syntaxPath.split('/')) {
                val fields = part.split(',')
                if (fields.size != 4 || fields[0].isBlank()) return original
                val node = nodes[fields[0]]
                if (node == null) {
                    val created = Node(fields[0], fields[1], fields[2], fields[3])
                    parent.children.add(created)
                    nodes[fields[0]] = created
                    parent = created
                } else {
                    if (node !in parent.children || node.category != fields[1] ||
                        node.role != fields[2] || node.rule != fields[3]) return original
                    parent = node
                }
            }
            parent.children.add(Node("", "", word.role, "", word = index))
        }

        val result = original.toMutableList()
        fun members(node: Node): List<Int> = node.word?.let(::listOf)
            ?: node.children.flatMap(::members).sorted()
        fun positions(indices: List<Int>) = indices.map { result.indexOf(it) }.sorted()
        fun contiguous(indices: List<Int>): Boolean {
            val positions = positions(indices)
            return positions.isNotEmpty() && positions.last() - positions.first() + 1 == positions.size
        }
        fun replace(indices: List<Int>, ordered: List<Int>) {
            val positions = result.indices.filter { result[it] in indices }
            positions.zip(ordered).forEach { (position, index) -> result[position] = index }
        }
        fun visit(node: Node, inNominal: Boolean, subordinate: Boolean, incomplete: Boolean) {
            val nominal = inNominal || node.category.removePrefix("!") == "np"
            val sub = subordinate || node.rule.equals("sub-CL", ignoreCase = true)
            val partial = incomplete || node.category.startsWith("!")
            // MACULA can place a postpositive conjunction before its clause in the
            // tree even though it interrupts the clause's first adverb in the text.
            if (node.category == "cl" && !nominal && !(mode.verbSecond && sub) && node.children.size == 2) {
                val conjunction = node.children.singleOrNull { it.word?.let { i -> words[i].pos == "conj" } == true }
                val clause = node.children.singleOrNull { it.category == "cl" }
                if (conjunction != null && clause != null) {
                    val conj = conjunction.word!!
                    val clauseWords = members(clause)
                    val all = (clauseWords + conj).sorted()
                    if (clauseWords.size > 1 && contiguous(all) && conj == clauseWords.first() + 1 &&
                        words[clauseWords.first()].role == "adv" && words[clauseWords.first()].pos == "adv"
                    ) {
                        replace(all, listOf(conj) + result.filter { it in clauseWords })
                    }
                }
            }
            node.children.forEach { visit(it, nominal, sub, partial) }
            val all = members(node)
            if (mode == WordOrderMode.ENGLISH_SVO && !partial && node.category == "np" &&
                node.rule.equals("NpAdjp", ignoreCase = true) && node.children.size == 2 && contiguous(all)
            ) {
                val noun = node.children[0]
                val adjective = node.children[1]
                val nounWords = members(noun)
                val adjectiveWords = members(adjective)
                val simpleNoun = noun.word?.let { words[it].pos == "noun" } == true ||
                    (noun.category == "np" && noun.rule.equals("DetNP", ignoreCase = true) &&
                        noun.children.size == 2 && noun.children.all { it.word != null } &&
                        noun.children.map { words[it.word!!].pos } == listOf("det", "noun"))
                val simpleAdjective = adjective.word?.let { words[it].pos == "adj" } == true ||
                    (adjective.category == "adjp" && adjectiveWords.size == 1 &&
                        adjective.children.singleOrNull()?.word?.let { words[it].pos == "adj" } == true)
                if (simpleNoun && simpleAdjective && contiguous(nounWords) && contiguous(adjectiveWords) &&
                    positions(nounWords).last() + 1 == positions(adjectiveWords).first()
                ) {
                    val determiner = nounWords.takeWhile { words[it].pos == "det" }
                    replace(all, determiner + adjectiveWords + nounWords.drop(determiner.size))
                }
            }
            if (mode == WordOrderMode.ENGLISH_SVO && !partial && node.category == "np" &&
                node.rule.equals("NPDetAdj", ignoreCase = true) && node.children.size == 2 && contiguous(all)
            ) {
                val noun = node.children[0]
                val modifier = node.children[1]
                val nounIndex = noun.word
                val modifierWords = members(modifier)
                if (nounIndex != null && words[nounIndex].pos == "noun" &&
                    modifier.category == "np" && modifier.rule.equals("DetAdj", ignoreCase = true) &&
                    modifier.children.size == 2 && modifier.children.all { it.word != null } &&
                    modifier.children.map { words[it.word!!].pos } == listOf("det", "adj") &&
                    contiguous(modifierWords) &&
                    positions(listOf(nounIndex)).last() + 1 == positions(modifierWords).first()
                ) {
                    replace(all, result.filter { it in modifierWords } + nounIndex)
                }
            }
            if (mode.postpositions && node.category == "pp" && contiguous(all) &&
                words[all.first()].pos == "prep" && all.size > 1
            ) {
                val current = result.filter { it in all }
                replace(all, current.filter { it != all.first() } + all.first())
            }
            if (node.category != "cl" || nominal || (mode.verbSecond && sub)) return

            data class Constituent(val role: String, val indices: List<Int>, val category: String)
            val parts = node.children.map { child ->
                val indices = members(child)
                val role = when (child.role.lowercase()) {
                    "s" -> "S"
                    "v", "vc" -> "V"
                    "o", "p" -> "O"
                    "io" -> "IO"
                    "adv" -> "ADV"
                    else -> ""
                }
                Constituent(
                    if (child.category == "cl" || child.category.startsWith("!") || !contiguous(indices)) "" else role,
                    indices, child.category
                )
            }.sortedBy { positions(it.indices).firstOrNull() ?: Int.MAX_VALUE }

            fun reorder(segment: List<Constituent>) {
                if (segment.count { it.role == "V" } != 1 || segment.size < 2) return
                val merged = mutableListOf<Constituent>()
                for (part in segment) {
                    if (!mode.verbSecond && part.role == "V" && merged.lastOrNull()?.role == "ADV") {
                        val modifier = merged.removeAt(merged.lastIndex)
                        merged.add(Constituent("V", modifier.indices + part.indices, ""))
                    } else merged.add(part)
                }
                val leading = merged.firstOrNull()?.takeIf {
                    it.category == "pp" && it.role in setOf("O", "ADV")
                }
                val rest = if (leading == null) merged else merged.drop(1)
                var sorted = rest.sortedBy { slots.indexOf(it.role).let { rank -> if (rank < 0) slots.size else rank } }
                if (mode.verbSecond) {
                    val lead = leading ?: sorted.firstOrNull { it.role != "V" }
                    if (lead != null) {
                        sorted = listOf(lead) + sorted.filter { it.role == "V" } +
                            sorted.filter { it !== lead && it.role != "V" }
                    }
                } else if (leading != null) {
                    sorted = listOf(leading) + sorted
                }
                val indexes = segment.flatMap { it.indices }
                val reordered = sorted.flatMap { part -> result.filter { it in part.indices } }
                replace(indexes, reordered)
            }
            val segment = mutableListOf<Constituent>()
            for (part in parts) {
                if (part.role.isEmpty() || (segment.isNotEmpty() &&
                    positions(segment.last().indices).last() + 1 != positions(part.indices).first())
                ) {
                    reorder(segment)
                    segment.clear()
                }
                if (part.role.isNotEmpty()) segment.add(part)
            }
            reorder(segment)
        }
        visit(root, false, false, false)
        return result
    }

    private fun validOrder(order: List<Int>, count: Int) =
        order.size == count && order.toSet().size == count && order.all { it in 0 until count }

    private fun clauses(chunks: List<GrammarChunk>): List<Int> {
        var clause = 0
        return chunks.mapIndexed { index, chunk ->
            if (index > 0 && chunk.slot == "CONJ") clause++
            clause
        }
    }
}
