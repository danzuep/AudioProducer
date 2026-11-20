package io.github.danzuep.bibleaudioproducer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicalGreekSpeechTest {
    @Test
    fun transcribesClassicalVowelsAndDiphthongsDistinctly() {
        assertEquals("ɛːiy", ClassicalGreekG2p.transcribe("ηιυ"))
        assertEquals("ai̯", ClassicalGreekG2p.transcribe("αι"))
        assertEquals("eː", ClassicalGreekG2p.transcribe("ει"))
        assertEquals("oi̯", ClassicalGreekG2p.transcribe("οι"))
        assertEquals("aːiːyː", listOf("α\u0304", "ι\u0304", "υ\u0304")
            .joinToString("") { ClassicalGreekG2p.transcribe(it) })
    }

    @Test
    fun preservesRoughBreathingStressAndNasalGamma() {
        assertEquals("ha", ClassicalGreekG2p.transcribe("ἁ"))
        assertEquals("tʰeˈos", ClassicalGreekG2p.transcribe("θεός"))
        assertEquals("ˈaŋɡelos", ClassicalGreekG2p.transcribe("ἄγγελος"))
    }

    @Test
    fun makesItalianRespellingForTheDeviceFallback() {
        assertEquals("teós", ClassicalGreekG2p.italianRespelling("θεός"))
        assertEquals("lógos", ClassicalGreekG2p.italianRespelling("λόγος"))
    }
}
