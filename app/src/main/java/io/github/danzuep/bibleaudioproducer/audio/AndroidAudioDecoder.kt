package io.github.danzuep.bibleaudioproducer.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio.SAMPLE_RATE
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AndroidAudioDecoder {
    fun decode(file: File): FloatArray {
        require(file.isFile && file.length() > 0) { "A recording is missing or empty." }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("The recording has no audio track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return decodeTrack(codec, extractor)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeTrack(codec: MediaCodec, extractor: MediaExtractor): FloatArray {
        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var rate = 0
        var channels = 0
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!outputEnded) {
            check(SystemClock.elapsedRealtime() < deadline) { "Decoding the recording took too long." }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(index))
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val next = codec.outputFormat
                    val nextRate = next.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val nextChannels = next.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val nextEncoding = if (next.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        next.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else AudioFormat.ENCODING_PCM_16BIT
                    check(output.size() == 0 || (rate == nextRate && channels == nextChannels && encoding == nextEncoding)) {
                        "The recording changes audio format during playback."
                    }
                    rate = nextRate
                    channels = nextChannels
                    encoding = nextEncoding
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            check(output.size().toLong() + info.size <= MAX_PCM_BYTES) {
                                "The recording is too long to render."
                            }
                            val buffer = requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            output.write(bytes)
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
        return toMono(output.toByteArray(), rate, channels, encoding)
    }

    private fun toMono(bytes: ByteArray, rate: Int, channels: Int, encoding: Int): FloatArray {
        require(rate in 8000..192000 && channels in 1..8) { "Unsupported recording format." }
        val sampleBytes = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IllegalArgumentException("Unsupported decoded audio format.")
        }
        require(bytes.isNotEmpty() && bytes.size % (sampleBytes * channels) == 0) {
            "The decoded recording is empty or incomplete."
        }
        val frames = bytes.size / (sampleBytes * channels)
        val outputFrames = (frames.toLong() * SAMPLE_RATE / rate).toInt()
        require(outputFrames in 1..MAX_SAMPLES) { "The recording is too long to render." }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val mono = FloatArray(frames) {
            var sum = 0f
            repeat(channels) {
                sum += if (sampleBytes == 2) input.short / 32768f else input.float
            }
            sum / channels
        }
        return FloatArray(outputFrames) { index ->
            val position = index.toDouble() * rate / SAMPLE_RATE
            val left = position.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (position - left).toFloat()
            mono[left] * (1f - fraction) + mono[right] * fraction
        }
    }

    companion object {
        private const val MAX_PCM_BYTES = 32L * 1024 * 1024
        private const val MAX_SAMPLES = SAMPLE_RATE * 60
    }
}
