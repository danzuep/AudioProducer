package io.github.danzuep.bibleaudioproducer.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

internal class AndroidAudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(file: File) {
        check(recorder == null) { "A recording is already in progress." }
        val directory = checkNotNull(file.parentFile)
        check(directory.mkdirs() || directory.isDirectory) { "Unable to create the recordings folder." }
        val instance = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            instance.setAudioSource(MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setAudioEncodingBitRate(96000)
            instance.setAudioSamplingRate(44100)
            instance.setAudioChannels(1)
            instance.setOutputFile(file.absolutePath)
            instance.prepare()
            instance.start()
            output = file
            recorder = instance
        } catch (exception: Exception) {
            instance.release()
            file.delete()
            throw exception
        }
    }

    fun stop(): File {
        val current = checkNotNull(recorder) { "No recording is in progress." }
        val file = checkNotNull(output)
        recorder = null
        output = null
        try {
            current.stop()
            check(file.isFile && file.length() > 0) { "The recording is empty." }
            return file
        } catch (exception: Exception) {
            file.delete()
            throw exception
        } finally {
            current.release()
        }
    }
}
