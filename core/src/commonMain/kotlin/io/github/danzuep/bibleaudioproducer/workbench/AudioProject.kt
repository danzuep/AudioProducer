package io.github.danzuep.bibleaudioproducer.workbench

import io.github.danzuep.bibleaudioproducer.audio.NeuralStrength

data class AudioProjectState(
    val rules: Map<String, GrammarRule> = emptyMap(),
    val seedOrders: Map<String, List<Int>> = emptyMap(),
    val unspoken: Set<String> = emptySet(),
    val wordOrders: Map<String, List<Int>> = emptyMap(),
    val neuralAudio: Boolean = false,
    val neuralOff: Set<String> = emptySet(),
    val neuralStrength: NeuralStrength = NeuralStrength.STANDARD
)

data class AudioArchiveImport(
    val language: String,
    val state: AudioProjectState,
    val added: Int,
    val skipped: Int
)
