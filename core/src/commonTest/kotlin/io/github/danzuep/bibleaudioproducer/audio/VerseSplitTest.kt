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

    @Test
    fun sliceFadesBothEndsWithoutChangingSource() {
        val source = FloatArray(200) { 1f }
        val slice = VerseAudio.slice(source, 10, 190)
        assertEquals(180, slice.size)
        assertEquals(0f, slice.first())
        assertEquals(0f, slice.last())
        assertEquals(1f, slice[80])
        assertTrue(source.all { it == 1f })
        assertFailsWith<IllegalArgumentException> { VerseAudio.slice(source, 10, 10) }
    }
}
