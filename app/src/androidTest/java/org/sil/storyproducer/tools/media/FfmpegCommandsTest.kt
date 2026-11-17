package org.sil.storyproducer.tools.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class FfmpegCommandsTest {
    private lateinit var directory: File
    private lateinit var audio: File
    private lateinit var image: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "ffmpeg test")
        check(directory.mkdirs() || directory.isDirectory)
        audio = File(directory, "narration.mp3")
        image = File(directory, "image.jpg")
        ZipInputStream(context.assets.open("000 Storm - demo.bloomSource")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = when (entry.name) {
                    "audio/narration0.mp3" -> audio
                    "1.jpg" -> image
                    else -> null
                }
                target?.outputStream()?.use { zip.copyTo(it) }
            }
        }
        check(audio.isFile && image.isFile)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun concatenatesMp3AndConvertsVideoTo3gp() {
        val list = File(directory, "concat.txt")
        val escapedPath = audio.absolutePath.replace("'", "'\\''")
        list.writeText("file '$escapedPath'\nfile '$escapedPath'\n")
        val combined = File(directory, "combined.mp3")
        execute(FfmpegCommands.concatenateAudio(list, combined))
        val originalInfo = checkNotNull(FFprobeKit.getMediaInformation(audio.absolutePath).mediaInformation)
        val combinedInfo = checkNotNull(FFprobeKit.getMediaInformation(combined.absolutePath).mediaInformation)
        assertEquals("mp3", combinedInfo.streams.first().codec)
        assertTrue(combinedInfo.duration.toDouble() > originalInfo.duration.toDouble() * 1.8)

        val video = File(directory, "source.mp4")
        execute(arrayOf(
            "-loop", "1", "-i", image.absolutePath, "-i", audio.absolutePath,
            "-t", "1", "-c:v", "mpeg4", "-c:a", "aac", video.absolutePath
        ))
        val converted = File(directory, "converted.3gp")
        execute(FfmpegCommands.convertTo3gp(video, converted))
        val streams = checkNotNull(FFprobeKit.getMediaInformation(converted.absolutePath).mediaInformation).streams
        val videoStream = streams.first { it.type == "video" }
        assertEquals("h263", videoStream.codec)
        assertEquals(176L, videoStream.width.toLong())
        assertEquals(144L, videoStream.height.toLong())
        assertEquals("aac", streams.first { it.type == "audio" }.codec)
    }

    private fun execute(arguments: Array<String>) {
        val session = FFmpegKit.executeWithArguments(arguments)
        assertTrue(session.allLogsAsString, ReturnCode.isSuccess(session.returnCode))
    }
}
