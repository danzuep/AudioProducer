package io.github.danzuep.bibleaudioproducer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class GreekTransliteratorTest {
    @Test
    fun transliteratesPolytonicFormsAndPreservesWordBoundaries() {
        assertEquals("Hē logos", GreekTransliterator.transliterate("Ἡ λόγος"))
        assertEquals("autos, ēi", GreekTransliterator.transliterate("αὐτός, ᾖ"))
    }

    @Test
    fun marksNasalGammaAndInitialRoughRho() {
        assertEquals("angelos rhētōr", GreekTransliterator.transliterate("ἄγγελος ῥήτωρ"))
    }
}
