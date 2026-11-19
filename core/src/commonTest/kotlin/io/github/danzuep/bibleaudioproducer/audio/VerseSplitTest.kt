package io.github.danzuep.bibleaudioproducer.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerseSplitTest {
    @Test
    fun boundariesCoverAudioInOrderAndPreferQuietGaps() {
        val samples = FloatArray(48000) { 0.5f }
        for (i in 15840 until 16000) samples[i] = 0f
        for (i in 31840 until 32000) samples[i] = 0f
        val boundaries = VerseAudio.suggestBoundaries(samples, 3)
        assertEquals(listOf(0, 15840, 31840, 48000), boundaries)
        assertTrue(boundaries.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun rejectsTooShortOrInvalidAudio() {
        assertFailsWith<IllegalArgumentException> { VerseAudio.suggestBoundaries(FloatArray(2), 3) }
        assertFailsWith<IllegalArgumentException> { VerseAudio.suggestBoundaries(floatArrayOf(Float.NaN), 1) }
    }
}
