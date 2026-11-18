package io.github.danzuep.bibleaudioproducer.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerseAudioTest {
    @Test
    fun joinsNormalizedClipsWithBoundedCrossfade() {
        val joined = VerseAudio.assemble(listOf(FloatArray(1000) { 0.25f }, FloatArray(1000) { 0.5f }))
        assertEquals(1520, joined.size)
        assertTrue(kotlin.math.abs(joined.first() - 0.12f) < 0.0001f)
        assertTrue(kotlin.math.abs(joined.last() - 0.12f) < 0.0001f)
        assertTrue(joined[750] > 0.12f)
    }

    @Test
    fun dropsSilentClipsAndKeepsMarginsAroundSpeech() {
        val speech = FloatArray(4000) { if (it in 1800..2199) 0.2f else 0f }
        val joined = VerseAudio.assemble(listOf(FloatArray(100), speech))
        assertEquals(1680, joined.size)
        assertFailsWith<IllegalArgumentException> { VerseAudio.assemble(listOf(FloatArray(20))) }
    }

    @Test
    fun emitsMonoPcmWavAndClampsSamples() {
        val bytes = VerseAudio.encodeWav(floatArrayOf(-2f, 0f, 2f))
        assertEquals("RIFF", bytes.decodeToString(0, 4))
        assertEquals("WAVEfmt ", bytes.decodeToString(8, 16))
        assertEquals("data", bytes.decodeToString(36, 40))
        assertContentEquals(byteArrayOf(6, 0, 0, 0), bytes.copyOfRange(40, 44))
        assertContentEquals(byteArrayOf(1, -128, 0, 0, -1, 127), bytes.copyOfRange(44, 50))
        assertFailsWith<IllegalArgumentException> { VerseAudio.encodeWav(floatArrayOf(Float.NaN)) }
    }

    @Test
    fun blendsTheNeuralSignalWithoutAcceptingSilentOrInvalidOutput() {
        val samples = VerseAudio.blendNeural(floatArrayOf(1f, 0.5f), floatArrayOf(0f, -1f))
        assertTrue(kotlin.math.abs(samples[0] - 0.52f) < 0.0001f)
        assertTrue(kotlin.math.abs(samples[1] + 0.02f) < 0.0001f)
        assertFailsWith<IllegalArgumentException> { VerseAudio.blendNeural(floatArrayOf(1f), floatArrayOf(0f)) }
    }
}
