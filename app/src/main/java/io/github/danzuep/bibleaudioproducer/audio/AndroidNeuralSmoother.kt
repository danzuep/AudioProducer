package io.github.danzuep.bibleaudioproducer.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Build
import java.io.File
import java.nio.FloatBuffer

internal class AndroidNeuralSmoother {
    fun smooth(samples: FloatArray, model: File, strength: NeuralStrength): FloatArray {
        require(Build.VERSION.SDK_INT >= 24) { "Neural smoothing requires Android 7.0 or later." }
        require(samples.size <= VerseAudio.SAMPLE_RATE * 30) { "Neural smoothing supports verses up to 30 seconds." }
        return try {
            runModel(samples, model, strength)
        } catch (error: LinkageError) {
            throw IllegalStateException("Neural smoothing is unavailable on this device.", error)
        }
    }

    private fun runModel(samples: FloatArray, model: File, strength: NeuralStrength): FloatArray {
        val mel = SpeechT5Mel.calculate(samples)
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            environment.createSession(model.absolutePath, options).use { session ->
                require(session.inputNames.size == 1) { "The neural model has an unsupported input." }
                val inputName = session.inputNames.single()
                val input = session.inputInfo.getValue(inputName).info as? TensorInfo
                    ?: throw IllegalArgumentException("The neural model has an unsupported input.")
                val shape = when (input.shape.size) {
                    2 -> longArrayOf(mel.frames.toLong(), SpeechT5Mel.BANDS.toLong())
                    3 -> longArrayOf(1, mel.frames.toLong(), SpeechT5Mel.BANDS.toLong())
                    else -> throw IllegalArgumentException("The neural model has an unsupported input shape.")
                }
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(mel.values), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val output = result[0] as? OnnxTensor
                            ?: throw IllegalStateException("The neural model returned unsupported output.")
                        val buffer = output.floatBuffer
                        require(buffer.remaining() in 1..VerseAudio.SAMPLE_RATE * 31) {
                            "The neural model returned an invalid audio length."
                        }
                        val generated = FloatArray(buffer.remaining())
                        buffer.get(generated)
                        return VerseAudio.blendNeural(samples, generated, strength.wetShare)
                    }
                }
            }
        }
    }
}
