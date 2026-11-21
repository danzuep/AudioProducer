package io.github.danzuep.bibleaudioproducer.audio

import android.media.MediaPlayer
import java.io.File

internal class AndroidAudioPlayer(private val onError: () -> Unit) {
    private var player: MediaPlayer? = null
    private var generation = 0
    private var prepared = false

    fun play(files: List<File>, onComplete: () -> Unit = {}) {
        require(files.isNotEmpty())
        stop()
        playNext(files, 0, generation, onComplete)
    }

    fun stop() {
        generation++
        prepared = false
        player?.release()
        player = null
    }

    fun pause(): Boolean {
        val current = player ?: return false
        if (!prepared) return false
        current.pause()
        return true
    }

    fun resume(): Boolean {
        val current = player ?: return false
        if (!prepared) return false
        current.start()
        return true
    }

    private fun playNext(files: List<File>, index: Int, expectedGeneration: Int, onComplete: () -> Unit) {
        if (generation != expectedGeneration) return
        if (index >= files.size) {
            onComplete()
            return
        }
        try {
            val instance = MediaPlayer()
            player = instance
            instance.setDataSource(files[index].absolutePath)
            instance.setOnPreparedListener {
                if (player === it) {
                    it.start()
                    prepared = true
                }
            }
            instance.setOnCompletionListener {
                if (player === it) {
                    player = null
                    prepared = false
                    it.release()
                    playNext(files, index + 1, expectedGeneration, onComplete)
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
