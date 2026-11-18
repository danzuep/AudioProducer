package io.github.danzuep.bibleaudioproducer.audio

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Base64
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

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

internal object AzureGreekSsml {
    fun create(text: String, voice: String = "it-IT-DiegoNeural"): String {
        require(text.isNotBlank()) { "Greek text cannot be empty." }
        require(voice.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid Azure voice name." }
        val ipa = ClassicalGreekG2p.transcribe(text)
        require(ipa.isNotBlank()) { "Unable to transcribe Greek text." }
        val escapedText = text.xmlEscape()
        val escapedIpa = ipa.xmlEscape()
        val locale = voice.substringBeforeLast('-')
        return """<speak version="1.0" xml:lang="$locale"><voice name="$voice"><phoneme alphabet="ipa" ph="$escapedIpa">$escapedText</phoneme></voice></speak>"""
    }

    private fun String.xmlEscape() = replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;")
}

internal class AzureGreekSpeech {
    fun synthesize(text: String, region: String, key: String, destination: File) {
        require(region.matches(Regex("[a-z0-9-]+"))) { "Enter a valid Azure Speech region." }
        require(key.isNotBlank()) { "Enter an Azure Speech subscription key in Settings." }
        val connection = URL("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", key)
            connection.setRequestProperty("Content-Type", "application/ssml+xml")
            connection.setRequestProperty("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            connection.outputStream.use { it.write(AzureGreekSsml.create(text).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Azure Speech returned HTTP $status${if (detail.isBlank()) "." else ": $detail"}")
            }

            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal class AndroidGreekTts(
    context: android.content.Context,
    private val onError: (String) -> Unit
) {
    private var ready = false
    private var failed = false
    private var pendingText: String? = null
    private lateinit var engine: TextToSpeech

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                onError("No compatible Italian speech voice is installed.")
            } else {
                val italianVoices = engine.voices.orEmpty().filter { it.locale.language == "it" }
                val voice = italianVoices.maxWithOrNull(
                    compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.quality })
                )
                if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                    failed = true
                    onError("No compatible Italian speech voice is installed.")
                } else {
                    ready = true
                    pendingText?.let(::speakNow)
                    pendingText = null
                }
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                onError("Android speech synthesis failed.")
            }
        })
    }

    fun speak(text: String) {
        when {
            ready -> speakNow(text)
            failed -> onError("No compatible Italian speech voice is installed.")
            else -> pendingText = text
        }
    }

    fun shutdown() = engine.shutdown()

    fun stop() = engine.stop()

    private fun speakNow(text: String) {
        val result = engine.speak(
            ClassicalGreekG2p.italianRespelling(text),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "greek-${System.nanoTime()}"
        )
        if (result == TextToSpeech.ERROR) onError("Android speech synthesis failed.")
    }
}

internal class SpeechCredentials(private val preferences: android.content.SharedPreferences) {
    fun region() = preferences.getString(REGION, "").orEmpty()

    fun save(region: String, key: String) {
        require(region.matches(Regex("[a-z0-9-]+"))) { "Enter a valid Azure Speech region." }
        val editor = preferences.edit().putString(REGION, region)
        if (key.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            editor.putString(ENCRYPTED_KEY, value)
        }
        check(editor.commit()) {
            "Unable to save Azure Speech settings."
        }
    }

    fun clearKey() {
        check(preferences.edit().remove(ENCRYPTED_KEY).commit()) {
            "Unable to clear the Azure Speech key."
        }
    }

    fun key(): String {
        val stored = preferences.getString(ENCRYPTED_KEY, null) ?: return ""
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            javax.crypto.spec.GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val REGION = "azure_speech_region"
        const val ENCRYPTED_KEY = "azure_speech_key"
        const val KEY_ALIAS = "azure_speech_key_encryption"
    }
}
