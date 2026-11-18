package io.github.danzuep.bibleaudioproducer.audio

import android.media.MediaPlayer
import java.io.File

internal class AndroidAudioPlayer(private val onError: () -> Unit) {
    private var player: MediaPlayer? = null
    private var generation = 0

    fun play(files: List<File>) {
        require(files.isNotEmpty())
        stop()
        playNext(files, 0, generation)
    }

    fun stop() {
        generation++
        player?.release()
        player = null
    }

    private fun playNext(files: List<File>, index: Int, expectedGeneration: Int) {
        if (index >= files.size || generation != expectedGeneration) return
        try {
            val instance = MediaPlayer()
            player = instance
            instance.setDataSource(files[index].absolutePath)
            instance.setOnPreparedListener { if (player === it) it.start() }
            instance.setOnCompletionListener {
                if (player === it) {
                    player = null
                    it.release()
                    playNext(files, index + 1, expectedGeneration)
                }
            }
            instance.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    stop()
                    onError()
                }
                true
            }
            instance.prepareAsync()
        } catch (exception: Exception) {
            stop()
            onError()
        }
    }
}
