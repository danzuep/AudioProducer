package io.github.danzuep.bibleaudioproducer.audio

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File

internal class AndroidGreekTts(
    context: android.content.Context,
    preferredVoiceName: String?,
    private val onVoiceChanged: (String?) -> Unit = {},
    private val onDone: () -> Unit = {},
    private val onError: (String) -> Unit
) {
    private var ready = false
    private var failed = false
    private var pendingSpeech: Pair<List<String>, (Boolean) -> Unit>? = null
    private var voices = emptyList<Voice>()
    private var selectedVoice: Voice? = null
    private val voiceCallbacks = mutableListOf<(List<Voice>) -> Unit>()
    private val fileCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val speechCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()
    private var closed = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val engine = TextToSpeech(context.applicationContext) { status ->
        main.post { if (!closed) initialize(status, preferredVoiceName) }
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                val callback = utteranceId?.let(fileCallbacks::remove)
                if (callback != null) callback(true) else utteranceId?.let { speechCallbacks.remove(it)?.invoke(true) }
                shutdownWhenIdle()
            }
            override fun onError(utteranceId: String?) {
                val callback = utteranceId?.let(fileCallbacks::remove)
                if (callback != null) callback(false) else utteranceId?.let { speechCallbacks.remove(it)?.invoke(false) }
                shutdownWhenIdle()
            }
        })
    }

    private fun initialize(status: Int, preferredVoiceName: String?) {
        val error = if (status != TextToSpeech.SUCCESS) {
            "Android speech synthesis could not be initialized."
        } else {
            voices = engine.voices.orEmpty()
                .filter { it.language() != null }
                .sortedWith(compareBy<Voice>({ it.name != preferredVoiceName }, { it.language() != "el" },
                    { it.isNetworkConnectionRequired }).thenByDescending { it.quality }.thenBy { it.name })
            val voice = voices.firstOrNull { engine.setVoice(it) == TextToSpeech.SUCCESS }
            if (voice == null) "Install a Greek voice for the preferred system TTS engine."
            else {
                selectedVoice = voice
                onVoiceChanged(voice.name)
                ready = true
                null
            }
        }
        failed = error != null
        val callbacks = voiceCallbacks.toList()
        voiceCallbacks.clear()
        callbacks.forEach { it(if (ready) voices else emptyList()) }
        val pending = pendingSpeech
        pendingSpeech = null
        if (error != null) {
            onError(error)
            pending?.second?.invoke(false)
        } else if (pending != null) speakNow(pending.first, pending.second)
    }

    private fun Voice.language() = when (locale.language) {
        "el", "ell", "gre" -> "el"
        else -> null
    }

    fun speak(text: String) = speak(listOf(text))

    fun speak(texts: List<String>, done: (Boolean) -> Unit = {
        if (it) onDone() else onError("Android speech synthesis failed.")
    }) {
        when {
            ready -> speakNow(texts, done)
            failed -> done(false)
            else -> pendingSpeech = texts to done
        }
    }

    // Renders the text to a cached WAV so it can be mixed with recordings; calls back on a TTS thread.
    fun synthesize(text: String, directory: File, done: (File?) -> Unit) {
        availableVoices { available ->
            val voice = selectedVoice
            if (closed || available.isEmpty() || voice == null) return@availableVoices done(null)
            val file = try {
                File.createTempFile("greek-tts-", ".wav", directory)
            } catch (exception: java.io.IOException) {
                onError("Unable to create a temporary Greek speech file: ${exception.message}")
                return@availableVoices done(null)
            }
            val id = "greek-file-${System.nanoTime()}"
            fileCallbacks[id] = { ok ->
                if (ok && file.length() > 0) done(file)
                else { file.delete(); done(null) }
            }
            val params = android.os.Bundle()
            usedFileSynthesis = true
            if (engine.synthesizeToFile(text, params, file, id) == TextToSpeech.ERROR) {
                fileCallbacks.remove(id)
                file.delete()
                done(null)
            }
        }
    }

    fun availableVoices(callback: (List<Voice>) -> Unit) {
        if (ready) callback(voices)
        else if (failed) callback(emptyList())
        else voiceCallbacks += callback
    }

    fun selectVoice(name: String): Boolean {
        val voice = voices.firstOrNull { it.name == name } ?: return false
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS) return false
        selectedVoice = voice
        onVoiceChanged(voice.name)
        return true
    }

    fun selectedVoiceName(): String? = selectedVoice?.name

    fun shutdown() {
        closed = true
        pendingSpeech = null
        speechCallbacks.clear()
        voiceCallbacks.clear()
        if (fileCallbacks.isEmpty()) {
            if (ready) engine.stop()
            shutdownWhenIdle()
        } else {
            main.postDelayed({
                fileCallbacks.values.toList().forEach { it(false) }
                fileCallbacks.clear()
                shutdownWhenIdle()
            }, 10_000)
        }
    }

    fun stop() {
        pendingSpeech = null
        speechCallbacks.clear()
        // Stopping the Pico engine mid-synthesis can crash it, so let file requests finish.
        if (ready && fileCallbacks.isEmpty()) engine.stop()
    }

    private var engineShutDown = false

    // Unbinding Pico after synthesizeToFile segfaults the engine process and steals window focus.
    private var usedFileSynthesis = false


    private fun shutdownWhenIdle() {
        main.post {
            if (closed && fileCallbacks.isEmpty() && !engineShutDown && !usedFileSynthesis) {
                engineShutDown = true
                engine.shutdown()
            }
        }
    }

    private fun speakNow(texts: List<String>, done: (Boolean) -> Unit) {
        if (texts.isEmpty()) {
            done(true)
            return
        }
        val ids = texts.indices.map { "greek-${System.nanoTime()}-$it" }
        val remaining = java.util.concurrent.atomic.AtomicInteger(texts.size)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback: (Boolean) -> Unit = { success ->
            if ((!success || remaining.decrementAndGet() == 0) && finished.compareAndSet(false, true)) {
                ids.forEach(speechCallbacks::remove)
                done(success)
            }
        }
        ids.forEach { speechCallbacks[it] = callback }
        texts.forEachIndexed { index, text ->
            val result = engine.speak(
                text,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                ids[index]
            )
            if (result == TextToSpeech.ERROR) {
                callback(false)
                return
            }
        }
    }
}
