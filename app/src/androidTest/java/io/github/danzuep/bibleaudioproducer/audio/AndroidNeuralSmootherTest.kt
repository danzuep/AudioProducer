package io.github.danzuep.bibleaudioproducer.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AndroidNeuralSmootherTest {
    @Test
    @SdkSuppress(minSdkVersion = 24)
    fun rendersWithTheVerifiedSpeechT5Model() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testNeuralModel") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.filesDir, "test-model.onnx")
        val directory = File(context.cacheDir, "neural-test")
        try {
            val store = NeuralModelStore(directory)
            source.inputStream().use { store.install(it) }
            assertTrue(store.isInstalled())
            val dry = FloatArray(16000) { (0.25 * sin(2 * PI * 440 * it / 16000)).toFloat() }
            val output = AndroidNeuralSmoother().smooth(dry, store.modelFile, NeuralStrength.STANDARD)
            assertEquals(dry.size, output.size)
            assertTrue(output.all { it.isFinite() })
            assertTrue(output.indices.any { kotlin.math.abs(output[it] - dry[it]) > 0.01f })
        } finally {
            directory.deleteRecursively()
            source.delete()
        }
    }
}
