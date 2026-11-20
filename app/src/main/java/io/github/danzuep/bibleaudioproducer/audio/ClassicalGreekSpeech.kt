package io.github.danzuep.bibleaudioproducer.audio

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.text.Normalizer

internal object ClassicalGreekG2p {
    private const val ACUTE = 0x0301
    private const val GRAVE = 0x0300
    private const val CIRCUMFLEX = 0x0342
    private const val ROUGH = 0x0314
    private const val DIAERESIS = 0x0308
    private const val IOTA_SUBSCRIPT = 0x0345
    private const val MACRON = 0x0304

    fun transcribe(text: String): String {
        val clusters = mutableListOf<Cluster>()
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).forEach { char ->
            if (Character.getType(char) == Character.NON_SPACING_MARK.toInt()) {
                if (clusters.isNotEmpty()) clusters.last().marks += char.code
            } else {
                clusters += Cluster(char)
            }
        }
        if (clusters.isEmpty()) return ""

        val phones = StringBuilder()
        var index = 0
        var lastVowelEnd = 0
        while (index < clusters.size) {
            val current = clusters[index]
            val next = clusters.getOrNull(index + 1)
            val diphthong = next?.let { diphthong(current, it) }
            if (diphthong != null) {
                if (index == 0 && (current.has(ROUGH) || next?.has(ROUGH) == true)) phones.append('h')
                if (current.isStressed() || next?.isStressed() == true) {
                    phones.insert(
                        stressPosition(phones, lastVowelEnd),
                        'ˈ'
                    )
                }
                phones.append(diphthong)
                lastVowelEnd = phones.length
                index += 2
                continue
            }
            if (index == 0 && current.base == 'ρ' && current.has(ROUGH)) phones.append('h')
            val gammaCluster = if (current.base == 'γ') {
                when (next?.base) {
                    'γ' -> "ŋɡ"
                    'κ' -> "ŋk"
                    'ξ' -> "ŋks"
                    'χ' -> "ŋkʰ"
                    else -> null
                }
            } else {
                null
            }
            if (gammaCluster != null) {
                phones.append(gammaCluster)
                index += 2
                continue
            }
            if (current.base == 'ρ' && index > 0 && clusters[index - 1].base == 'ρ' &&
                current.has(ROUGH)
            ) {
                phones.append("rh")
                index++
                continue
            }
            if (current.isVowel()) {
                if (current.has(ROUGH) && index == 0) phones.append('h')
                if (current.isStressed()) {
                    phones.insert(
                        stressPosition(phones, lastVowelEnd),
                        'ˈ'
                    )
                }
                phones.append(vowel(current))
                lastVowelEnd = phones.length
            } else {
                phones.append(
                    when (current.base) {
                        'β' -> "b"
                        'γ' -> "ɡ"
                        'δ' -> "d"
                        'ζ' -> "zd"
                        'θ' -> "tʰ"
                        'κ' -> "k"
                        'λ' -> "l"
                        'μ' -> "m"
                        'ν' -> "n"
                        'ξ' -> "ks"
                        'π' -> "p"
                        'ρ' -> "r"
                        'σ', 'ς' -> "s"
                        'τ' -> "t"
                        'φ' -> "pʰ"
                        'χ' -> "kʰ"
                        'ψ' -> "ps"
                        else -> current.base.toString()
                    }
                )
            }
            index++
        }
        return phones.toString()
    }
    private fun stressPosition(phones: StringBuilder, lastVowelEnd: Int): Int {
        val start = if (lastVowelEnd == 0) 0 else lastVowelEnd
        val onset = phones.substring(start)
        if (start == 0 || onset.length < 2) return start
        val pair = onset.takeLast(2)
        return if (pair in setOf(
                "pr", "br", "tr", "dr", "kr", "ɡr", "pl", "bl", "kl", "ɡl", "st"
            )
        ) {
            phones.length - 2
        } else {
            phones.length - 1
        }
    }

    fun italianRespelling(text: String): String {
        val ipa = transcribe(text)
        val output = StringBuilder()
        var stressed = false
        var index = 0
        while (index < ipa.length) {
            if (ipa[index] == 'ˈ') {
                stressed = true
                index++
                continue
            }
            val phone = listOf(
                "ŋks", "ŋkʰ", "ŋɡ", "ai̯", "oi̯", "au̯", "eu̯", "yi̯",
                "tʰ", "pʰ", "kʰ", "ɛː", "ɔː", "eː"
            )
                .firstOrNull { ipa.startsWith(it, index) }
            val chunk = phone ?: ipa[index].toString()
            val nextPhone = ipa.getOrNull(index + chunk.length)
            val mapped = when (chunk) {
                "ŋks" -> "ncs"
                "ŋkʰ" -> "nch"
                "ŋɡ" -> "ngh"
                "tʰ" -> "t"
                "pʰ" -> "p"
                "kʰ" -> if (nextPhone in listOf('e', 'i')) "ch" else "c"
                "ɛː" -> italianVowel("e", stressed, open = true).also { stressed = false }
                "ɔː" -> italianVowel("o", stressed, open = true).also { stressed = false }
                "eː" -> italianVowel("e", stressed).also { stressed = false }
                "ai̯", "oi̯", "au̯", "eu̯", "yi̯" -> {
                    val vowels = chunk.replace("̯", "").replace("y", "u")
                    val result = italianVowel(vowels.substring(0, 1), stressed) + vowels.substring(1)
                    stressed = false
                    result
                }
                "ː", "̯" -> ""
                "ɡ" -> if (nextPhone in listOf('e', 'i')) "gh" else "g"
                "ŋ" -> "n"
                "θ" -> "t"
                "φ" -> "f"
                "χ" -> if (nextPhone in listOf('e', 'i')) "ch" else "c"
                "y" -> "u"
                "a", "e", "i", "o", "u" -> italianVowel(chunk, stressed).also { stressed = false }
                "k", "c" -> if (nextPhone in listOf('e', 'i')) "ch" else "c"
                "ʰ" -> ""
                "z" -> "z"
                else -> chunk
            }
            output.append(mapped)
            index += chunk.length
        }
        return output.toString()
    }

    private fun italianVowel(vowel: String, stressed: Boolean, open: Boolean = false): String {
        if (!stressed) return vowel
        return when (vowel) {
            "a" -> "á"
            "e" -> if (open) "è" else "é"
            "i" -> "í"
            "o" -> if (open) "ò" else "ó"
            else -> "ú"
        }
    }

    private fun diphthong(first: Cluster, second: Cluster): String? {
        if (first.marks.contains(DIAERESIS) || second.marks.contains(DIAERESIS)) return null
        val result = when ("${first.base}${second.base}") {
            "αι" -> "ai̯"
            "ει" -> "eː"
            "οι" -> "oi̯"
            "αυ" -> "au̯"
            "ευ" -> "eu̯"
            "ου" -> "uː"
            "υι" -> "yi̯"
            "ηυ" -> "ɛːu̯"
            else -> return null
        }
        return result
    }

    private fun vowel(cluster: Cluster): String = when {
        cluster.has(IOTA_SUBSCRIPT) -> when (cluster.base) {
            'α' -> "aːi̯"
            'η' -> "ɛːi̯"
            'ω' -> "ɔːi̯"
            else -> ""
        }
        else -> when (cluster.base) {
            'α' -> if (cluster.has(MACRON)) "aː" else "a"
            'ε' -> "e"
            'η' -> "ɛː"
            'ι' -> if (cluster.has(MACRON)) "iː" else "i"
            'ο' -> "o"
            'υ' -> if (cluster.has(MACRON)) "yː" else "y"
            'ω' -> "ɔː"
            else -> ""
        }
    }

    private fun Cluster.isVowel() = base in "αεηιουω"
    private fun Cluster.isStressed() = has(ACUTE) || has(GRAVE) || has(CIRCUMFLEX)
    private fun Cluster.has(mark: Int) = mark in marks

    private data class Cluster(val base: Char, val marks: MutableSet<Int> = mutableSetOf())
}

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
            val file = File.createTempFile("greek-tts-", ".wav", directory)
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
