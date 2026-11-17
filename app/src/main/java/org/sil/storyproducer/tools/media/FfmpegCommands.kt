package org.sil.storyproducer.tools.media

import java.io.File

internal object FfmpegCommands {
    fun concatenateAudio(list: File, output: File): Array<String> = arrayOf(
        "-f", "concat", "-safe", "0", "-i", list.absolutePath,
        "-c", "copy", output.absolutePath
    )

    fun convertTo3gp(input: File, output: File): Array<String> = arrayOf(
        "-i", input.absolutePath, "-f", "3gp", "-vcodec", "h263",
        "-r", "15", "-vf", "scale=176x144", "-acodec", "aac",
        "-b:v", "1000000", output.absolutePath
    )
}
