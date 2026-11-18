package io.github.danzuep.bibleaudioproducer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Audio assembly follows MaculaScripta's Apache-2.0 verse-audio.js.
object VerseAudio {
    const val SAMPLE_RATE = 16000

    fun assemble(clips: List<FloatArray>): FloatArray {
        require(clips.isNotEmpty()) { "No recordings are available for this verse." }
        val parts = clips.map { trimSilence(normalize(it)) }
        require(parts.all { it.isNotEmpty() }) { "A recording contains no audible audio." }
        val fades = parts.zipWithNext { a, b -> minOf(480, a.size / 2, b.size / 2) }
        val size = parts.sumOf { it.size.toLong() } - fades.sumOf { it.toLong() }
        require(size <= Int.MAX_VALUE) { "The verse is too long to render." }
        val result = FloatArray(size.toInt())
        var offset = 0
        parts.forEachIndexed { index, samples ->
            val into = fades.getOrElse(index - 1) { 0 }
            val out = fades.getOrElse(index) { 0 }
            for (i in samples.indices) {
                val gain = when {
                    i < into -> sin(PI / 2 * i / into)
                    i >= samples.size - out -> cos(PI / 2 * (i - (samples.size - out)) / out)
                    else -> 1.0
                }
                result[offset + i] += (samples[i] * gain).toFloat()
            }
            offset += samples.size - out
        }
        return result
    }

    fun encodeWav(samples: FloatArray): ByteArray {
        require(samples.size <= (Int.MAX_VALUE - 44) / 2) { "The verse is too long to export." }
        val bytes = ByteArray(44 + samples.size * 2)
        fun text(offset: Int, value: String) {
            value.forEachIndexed { index, char -> bytes[offset + index] = char.code.toByte() }
        }
        fun integer(offset: Int, value: Int, count: Int) {
            repeat(count) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
        }
        text(0, "RIFF")
        integer(4, bytes.size - 8, 4)
        text(8, "WAVEfmt ")
        integer(16, 16, 4)
        integer(20, 1, 2)
        integer(22, 1, 2)
        integer(24, SAMPLE_RATE, 4)
        integer(28, SAMPLE_RATE * 2, 4)
        integer(32, 2, 2)
        integer(34, 16, 2)
        text(36, "data")
        integer(40, samples.size * 2, 4)
        samples.forEachIndexed { index, value ->
            require(value.isFinite()) { "The verse contains invalid audio samples." }
            integer(44 + index * 2, (value.coerceIn(-1f, 1f) * 32767).roundToInt(), 2)
        }
        return bytes
    }

    fun blendNeural(dry: FloatArray, wet: FloatArray, wetShare: Float = 0.35f): FloatArray {
        require(dry.isNotEmpty() && wet.isNotEmpty()) { "Neural smoothing returned no audio." }
        require(wetShare in 0f..1f) { "Invalid neural smoothing strength." }
        require(dry.all { it.isFinite() } && wet.all { it.isFinite() }) { "Neural smoothing returned invalid audio." }
        val dryPeak = dry.maxOf { abs(it) }
        val wetPeak = wet.maxOf { abs(it) }
        require(wetPeak > 0f) { "Neural smoothing returned silent audio." }
        val dryGain = if (dryPeak == 0f) 1f else 0.8f / dryPeak
        val wetGain = 0.8f / wetPeak
        return FloatArray(minOf(dry.size, wet.size)) { i ->
            dry[i] * dryGain * (1f - wetShare) + wet[i] * wetGain * wetShare
        }
    }

    private fun normalize(samples: FloatArray): FloatArray {
        var sum = 0.0
        for (sample in samples) {
            require(sample.isFinite()) { "A recording contains invalid audio samples." }
            sum += sample.toDouble() * sample
        }
        val rms = sqrt(sum / samples.size.coerceAtLeast(1))
        if (rms == 0.0) return samples
        val gain = minOf(0.12 / rms, 8.0)
        return FloatArray(samples.size) { (samples[it] * gain).toFloat().coerceIn(-1f, 1f) }
    }

    private fun trimSilence(samples: FloatArray): FloatArray {
        val limit = (samples.maxOfOrNull { abs(it) } ?: 0f) * 0.02f
        var start = 0
        var end = samples.size
        while (start < end && abs(samples[start]) <= limit) start++
        while (end > start && abs(samples[end - 1]) <= limit) end--
        if (start == end) return FloatArray(0)
        return samples.copyOfRange((start - 640).coerceAtLeast(0), (end + 640).coerceAtMost(samples.size))
    }
}
