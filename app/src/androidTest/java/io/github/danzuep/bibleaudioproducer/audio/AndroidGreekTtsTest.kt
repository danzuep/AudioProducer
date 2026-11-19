package io.github.danzuep.bibleaudioproducer.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AndroidGreekTtsTest {
    @Test
    fun installedGreekVoiceCompletesAnUtterance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testGreekTts") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val complete = CountDownLatch(1)
        val failure = AtomicReference<String>()
        lateinit var tts: AndroidGreekTts
        instrumentation.runOnMainSync {
            tts = AndroidGreekTts(
                instrumentation.targetContext,
                preferredVoiceName = null,
                onDone = { complete.countDown() },
                onError = { failure.set(it); complete.countDown() }
            )
            tts.availableVoices { voices ->
                val greek = voices.firstOrNull {
                    it.locale.language in setOf("el", "ell", "gre") && !it.isNetworkConnectionRequired
                }
                if (greek == null || !tts.selectVoice(greek.name)) {
                    failure.set("No usable offline Greek voice: $voices")
                    complete.countDown()
                } else {
                    tts.speak("Ἐν ἀρχῇ ἦν ὁ λόγος")
                }
            }
        }
        try {
            assertTrue("The Greek voice did not complete an utterance", complete.await(20, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            instrumentation.runOnMainSync { tts.shutdown() }
        }
    }
}
