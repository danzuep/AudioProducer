package io.github.danzuep.bibleaudioproducer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeechT5MelTest {
    @Test
    fun matchesUpstreamSpeechT5Features() {
        val samples = FloatArray(1600) { (0.25 * sin(2 * PI * 440 * it / 16000)).toFloat() }
        val features = SpeechT5Mel.calculate(samples)
        assertEquals(7, features.frames)
        val indices = listOf(0, 1, 7, 20, 79, 80, 400, 559)
        val expected = listOf(-0.70768905f, -0.68452466f, -0.2038609f, -1.118727f,
            -2.8504503f, -1.0088636f, -1.2213154f, -2.873293f)
        indices.zip(expected).forEach { (index, value) ->
            assertTrue(abs(features.values[index] - value) < 0.0001f, "Feature $index differed")
        }
        assertFailsWith<IllegalArgumentException> { SpeechT5Mel.calculate(floatArrayOf()) }
    }
}
