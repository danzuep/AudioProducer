package io.github.danzuep.bibleaudioproducer.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class AndroidAudioEncoder {
    fun encode(samples: FloatArray, destination: File, format: VerseExportFormat) {
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "Invalid audio for encoding." }
        require(format != VerseExportFormat.WAV && Build.VERSION.SDK_INT >= format.minSdk) {
            "This audio format is not supported on this device."
        }
        val mime = when (format) {
            VerseExportFormat.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
            VerseExportFormat.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
            VerseExportFormat.WAV -> error("WAV does not require an encoder.")
        }
        val audioFormat = MediaFormat.createAudioFormat(mime, VerseAudio.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, if (format == VerseExportFormat.AAC) 48_000 else 32_000)
            if (format == VerseExportFormat.AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        val codec = MediaCodec.createEncoderByType(mime)
        try {
            val container = when (format) {
                VerseExportFormat.AAC -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                VerseExportFormat.OPUS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                VerseExportFormat.WAV -> error("WAV does not require a muxer.")
            }
            val muxer = MediaMuxer(destination.absolutePath, container)
            try {
                codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                // Short AAC takes need silence before and after the signal so
                // codec priming and frame buffering do not discard the word.
                val shortAac = format == VerseExportFormat.AAC && samples.size < 2048
                val leadingSamples = if (shortAac) 1024 else 0
                val inputSamples = if (shortAac) samples.size + 3072 else samples.size
                encodeTrack(codec, muxer, samples, leadingSamples, inputSamples)
            } finally {
                muxer.release()
            }
        } finally {
            codec.release()
        }
    }

    private fun encodeTrack(
        codec: MediaCodec, muxer: MediaMuxer, samples: FloatArray, leadingSamples: Int, inputSamples: Int
    ) {
        val info = MediaCodec.BufferInfo()
        var position = 0
        var inputEnded = false
        var outputEnded = false
        var track = -1
        var wroteAudio = false
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!outputEnded) {
            check(SystemClock.elapsedRealtime() < deadline) { "Encoding the verse took too long." }
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(index))
                    buffer.clear()
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val count = minOf(inputSamples - position, buffer.remaining() / 2)
                    check(count > 0 || position == inputSamples) { "The encoder input buffer is too small." }
                    for (i in 0 until count) {
                        val sample = samples.getOrElse(position + i - leadingSamples) { 0f }
                        buffer.putShort((sample.coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
                    }
                    val timestamp = position.toLong() * 1_000_000 / VerseAudio.SAMPLE_RATE
                    position += count
                    inputEnded = count == 0
                    codec.queueInputBuffer(
                        index, 0, count * 2, timestamp,
                        if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    )
                }
            }
            when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(track == -1) { "The encoder changed format unexpectedly." }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            check(track >= 0) { "The encoder did not provide an output format." }
                            val buffer = requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                            wroteAudio = true
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
        check(track >= 0 && wroteAudio) { "The encoder produced no audio." }
        muxer.stop()
    }
}
