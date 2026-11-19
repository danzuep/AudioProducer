package io.github.danzuep.bibleaudioproducer.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AndroidAudioDecoderTest {
    @Test
    fun decodesWavToMonoVerseSamples() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("verse-decoder-", ".wav", context.cacheDir)
        try {
            val samples = FloatArray(1600) { (0.25 * sin(2 * PI * 440 * it / VerseAudio.SAMPLE_RATE)).toFloat() }
            file.writeBytes(VerseAudio.encodeWav(samples))
            val decoded = AndroidAudioDecoder().decode(file)
            assertEquals(samples.size, decoded.size)
            assertEquals(samples[10], decoded[10], 0.001f)
            assertTrue(decoded.any { it > 0.2f })
        } finally {
            file.delete()
        }
    }

    @Test
    fun preservesVeryShortWordClip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("short-word-", ".wav", context.cacheDir)
        try {
            val samples = FloatArray(160) { (0.5 * sin(2 * PI * 440 * it / VerseAudio.SAMPLE_RATE)).toFloat() }
            file.writeBytes(VerseAudio.encodeWav(samples))
            val decoded = AndroidAudioDecoder().decode(file)
            assertEquals(samples.size, decoded.size)
            samples.indices.forEach { assertEquals(samples[it], decoded[it], 0.001f) }
        } finally {
            file.delete()
        }
    }
}
