package io.github.danzuep.bibleaudioproducer.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerseSplitTest {
    @Test
    fun movingSharedBoundaryUpdatesBothNeighborsAndRejectsCrossings() {
        val boundaries = VerseBoundaries(listOf(0, 100, 200, 300))
        assertEquals(setOf(0, 1), boundaries.move(1, 120))
        assertEquals(120, boundaries.end(0))
        assertEquals(120, boundaries.start(1))
        assertEquals(emptySet(), boundaries.move(1, 120))
        assertFailsWith<IllegalArgumentException> { boundaries.move(1, 200) }
        assertFailsWith<IllegalArgumentException> { boundaries.move(0, -1) }
        assertEquals(listOf(0, 120, 200, 300), boundaries.snapshot())
        assertEquals(setOf(0, 1, 2), boundaries.setRange(1, 130, 220))
        assertEquals(listOf(0, 130, 220, 300), boundaries.snapshot())
        assertFailsWith<IllegalArgumentException> { boundaries.setRange(1, 220, 220) }
        assertFailsWith<IllegalArgumentException> { boundaries.setRange(0, -1, 100) }
        assertEquals(listOf(0, 130, 220, 300), boundaries.snapshot())
    }

    @Test
    fun leadingSilenceIsSkippedWithoutLosingTheOriginalAudio() {
        val samples = FloatArray(24000) { if (it >= 16000) 0.4f else 0f }
        val suggested = VerseAudio.suggestBoundaries(samples, 2)
        assertEquals(15360, suggested.first())
        assertEquals(24000, suggested.last())
        val boundaries = VerseBoundaries(suggested)
        assertEquals(setOf(0), boundaries.move(0, 0))
        assertEquals(setOf(0), boundaries.setRange(0, 12000, boundaries.end(0)))
        assertEquals(0f, samples[0])
        assertEquals(0.4f, samples[16000])
        assertEquals(0, VerseAudio.suggestBoundaries(FloatArray(1000), 2).first())
    }

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
