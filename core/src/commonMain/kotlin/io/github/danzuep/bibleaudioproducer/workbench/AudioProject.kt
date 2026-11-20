package io.github.danzuep.bibleaudioproducer.workbench

data class AudioProjectState(
    val rules: Map<String, GrammarRule> = emptyMap(),
    val seedOrders: Map<String, List<Int>> = emptyMap(),
    val unspoken: Set<String> = emptySet(),
    val wordOrders: Map<String, List<Int>> = emptyMap(),
    val fullVerseReferences: Set<String> = emptySet(),
    val defaultWordOrder: String? = null
)

data class AudioArchiveImport(
    val language: String,
    val state: AudioProjectState,
    val added: Int,
    val skipped: Int
)
