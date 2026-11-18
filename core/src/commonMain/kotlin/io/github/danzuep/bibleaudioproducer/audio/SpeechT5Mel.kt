package io.github.danzuep.bibleaudioproducer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

// SpeechT5 preprocessing follows MaculaScripta's Apache-2.0 neural-resynth.js.
object SpeechT5Mel {
    const val BANDS = 80
    private const val FFT_SIZE = 1024
    private const val HOP = 256
    private val filters = makeFilters()
    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE) }

    data class Features(val values: FloatArray, val frames: Int)

    fun calculate(samples: FloatArray): Features {
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "Invalid audio for neural smoothing." }
        val padded = FloatArray(samples.size + FFT_SIZE)
        samples.copyInto(padded, FFT_SIZE / 2)
        for (i in 1..FFT_SIZE / 2) {
            padded[FFT_SIZE / 2 - i] = samples[i.coerceAtMost(samples.lastIndex)]
            padded[FFT_SIZE / 2 + samples.lastIndex + i] = samples[(samples.lastIndex - i).coerceAtLeast(0)]
        }
        val frames = 1 + samples.size / HOP
        val mel = FloatArray(frames * BANDS)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val magnitude = DoubleArray(FFT_SIZE / 2 + 1)
        for (frame in 0 until frames) {
            for (i in 0 until FFT_SIZE) real[i] = padded[frame * HOP + i] * window[i]
            imaginary.fill(0.0)
            fft(real, imaginary)
            for (bin in magnitude.indices) magnitude[bin] = hypot(real[bin], imaginary[bin])
            for (band in 0 until BANDS) {
                var sum = 0.0
                for (bin in magnitude.indices) sum += filters[band][bin] * magnitude[bin]
                mel[frame * BANDS + band] = log10(sum.coerceAtLeast(1e-10)).toFloat()
            }
        }
        return Features(mel, frames)
    }

    private fun makeFilters(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = if (hz < 1000) hz * 3 / 200 else 15 + ln(hz / 1000) / (ln(6.4) / 27)
        fun melToHz(mel: Double) = if (mel < 15) mel * 200 / 3 else 1000 * exp((mel - 15) * ln(6.4) / 27)
        val minimum = hzToMel(80.0)
        val maximum = hzToMel(7600.0)
        val points = DoubleArray(BANDS + 2) { melToHz(minimum + (maximum - minimum) * it / (BANDS + 1)) }
        return Array(BANDS) { band ->
            val low = points[band]
            val centre = points[band + 1]
            val high = points[band + 2]
            DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val hz = bin.toDouble() * VerseAudio.SAMPLE_RATE / FFT_SIZE
                maxOf(0.0, minOf((hz - low) / (centre - low), (high - hz) / (high - centre))) * 2 / (high - low)
            }
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realValue = real[i]
                real[i] = real[j]
                real[j] = realValue
                val imaginaryValue = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = imaginaryValue
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2 * PI / length
            for (start in 0 until size step length) {
                for (k in 0 until length / 2) {
                    val cosine = cos(angle * k)
                    val sine = sin(angle * k)
                    val a = start + k
                    val b = a + length / 2
                    val re = real[b] * cosine - imaginary[b] * sine
                    val im = real[b] * sine + imaginary[b] * cosine
                    real[b] = real[a] - re
                    imaginary[b] = imaginary[a] - im
                    real[a] += re
                    imaginary[a] += im
                }
            }
            length *= 2
        }
    }
}
