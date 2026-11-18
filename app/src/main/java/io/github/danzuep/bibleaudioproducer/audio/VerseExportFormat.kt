package io.github.danzuep.bibleaudioproducer.audio

import io.github.danzuep.bibleaudioproducer.R

internal enum class VerseExportFormat(val label: Int, val extension: String, val minSdk: Int) {
    WAV(R.string.audio_format_wav, "wav", 23),
    AAC(R.string.audio_format_aac, "m4a", 23),
    OPUS(R.string.audio_format_opus, "ogg", 29)
}
