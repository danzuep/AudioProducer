package io.github.danzuep.bibleaudioproducer.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AndroidAudioEncoderTest {
    @Test
    fun encodesAacM4a() = verify(VerseExportFormat.AAC, MediaFormat.MIMETYPE_AUDIO_AAC)

    @Test
    fun encodesShortWordClipsWithoutDroppingAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (size in listOf(160, 800, 1024)) {
            val file = File.createTempFile("short-word-", ".m4a", context.cacheDir)
            val samples = FloatArray(size) {
                (0.5 * sin(2 * PI * 440 * it / VerseAudio.SAMPLE_RATE)).toFloat()
            }
            try {
                AndroidAudioEncoder().encode(samples, file, VerseExportFormat.AAC)
                val decoded = AndroidAudioDecoder().decode(file)
                assertTrue("No audio for $size samples", decoded.any { abs(it) > 0.02f })
                assertTrue("Excessive padding for $size samples", decoded.size <= size + 4096)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun encodesOpusOgg() = verify(VerseExportFormat.OPUS, MediaFormat.MIMETYPE_AUDIO_OPUS)

    private fun verify(format: VerseExportFormat, mime: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("verse-encoder-", ".${format.extension}", context.cacheDir)
        val samples = FloatArray(VerseAudio.SAMPLE_RATE * 2) {
            (0.25 * sin(2 * PI * 440 * it / VerseAudio.SAMPLE_RATE)).toFloat()
        }
        try {
            AndroidAudioEncoder().encode(samples, file, format)
            assertTrue(file.length() > 0)
            assertTrue("Compressed audio should be smaller than WAV", file.length() < VerseAudio.encodeWav(samples).size)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertEquals(mime, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
            } finally {
                extractor.release()
            }
            val decoded = AndroidAudioDecoder().decode(file)
            assertTrue("Encoded duration changed significantly", abs(decoded.size - samples.size) <= 2048)
            assertTrue("Encoded audio is silent", decoded.any { abs(it) > 0.1f })
        } finally {
            file.delete()
        }
    }
}
