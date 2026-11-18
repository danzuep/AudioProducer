package io.github.danzuep.bibleaudioproducer.audio

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal class NeuralModelStore(private val directory: File) {
    val modelFile: File get() = File(directory, "speecht5-hifigan.onnx")
    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() == MODEL_SIZE

    fun install(input: InputStream) {
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create the model folder." }
        val temporary = File.createTempFile("neural-model-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    require(size <= MODEL_SIZE) { "This is not the supported neural model." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(size == MODEL_SIZE && hash == MODEL_SHA256) { "This is not the supported neural model." }
            check(temporary.renameTo(modelFile)) { "Unable to save the neural model." }
        } finally {
            if (temporary.exists()) check(temporary.delete()) { "Unable to remove the temporary neural model." }
        }
    }

    companion object {
        private const val MODEL_SIZE = 55_432_026L
        private const val MODEL_SHA256 = "26408d9b8b3e83e66a3ef614a5621b84cacdcabd81b3a6a2fa984eb788566e6c"
    }
}
