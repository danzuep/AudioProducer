package io.github.danzuep.bibleaudioproducer.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioDecoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.workbench.VerseRecordingWord
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** A take is private to this editor until the user explicitly saves each mapping. */
internal class VerseTakeEditor(
    private val activity: AppCompatActivity,
    private val repository: AudioProjectRepository,
    private val words: List<VerseRecordingWord>,
    private val spokenWordCount: Int,
    private val hasPermission: () -> Boolean,
    private val onBack: () -> Unit,
    private val onComplete: () -> Unit
) {
    private val io = Executors.newSingleThreadExecutor()
    private val recorder = AndroidAudioRecorder(activity)
    private val player = AndroidAudioPlayer { toast("Unable to play this audio.") }
    private var draft: File? = null
    private var preview: File? = null
    private var samples = FloatArray(0)
    private var starts = IntArray(0)
    private var ends = IntArray(0)
    private val reviewed = mutableSetOf<Int>()
    private var recording = false
    private var working = false
    private var closed = false
    private var discardConfirmed = false
    private var index = 0
    private var updating = false
    private lateinit var record: Button
    private lateinit var select: Spinner
    private lateinit var start: EditText
    private lateinit var end: EditText
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var status: TextView
    private lateinit var waveform: RecordingWaveform
    private lateinit var save: Button
    private lateinit var keep: Button
    private lateinit var finish: Button
    private lateinit var previewButton: Button
    private lateinit var previewWhole: Button
    private lateinit var previewOrdered: Button
    private lateinit var previewExisting: Button

    fun view(): View {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fun button(label: String, action: () -> Unit) = Button(activity).apply {
            text = label
            setOnClickListener { action() }
        }
        content.addView(button("Cancel recording") { if (!working) onBack() })
        content.addView(TextView(activity).apply {
            text = "Record the complete verse in its original spoken order, including disabled words. " +
                "Suggestions are approximate; review each clip in the selected card order."
        })
        record = button("Record verse") { toggleRecord() }
        content.addView(record)
        waveform = RecordingWaveform(activity)
        content.addView(waveform, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        status = TextView(activity)
        content.addView(status)
        select = Spinner(activity)
        content.addView(select)
        select.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updating && starts.isNotEmpty() && position != index) {
                    if (!rememberSelection()) {
                        updating = true
                        select.setSelection(index)
                        updating = false
                        return
                    }
                    index = position
                    showSelection()
                    finish.isEnabled = reviewed.size == words.size
                    status.text = "${reviewed.size} / ${words.size} clips reviewed"
                }
            }
        }
        fun timeField(label: String): EditText {
            content.addView(TextView(activity).apply { text = label })
            return EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                isSingleLine = true
                content.addView(this)
            }
        }
        start = timeField("Start (seconds)")
        startSeek = SeekBar(activity)
        content.addView(startSeek)
        end = timeField("End (seconds)")
        endSeek = SeekBar(activity)
        content.addView(endSeek)
        fun configureSeek(seek: SeekBar, field: EditText) {
            seek.max = 10000
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) field.setText(seconds((samples.size.toLong() * progress / 10000).toInt()))
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        configureSeek(startSeek, start)
        configureSeek(endSeek, end)
        previewButton = button("Preview selection") { previewSelection() }
        content.addView(previewButton)
        previewWhole = button("Preview whole verse") { previewSamples(0, samples.size) }
        content.addView(previewWhole)
        previewOrdered = button("Preview card order") { previewCardOrder() }
        content.addView(previewOrdered)
        previewExisting = button("Preview existing clip") {
            player.play(listOf(repository.clipFile(words[index].word.surface)))
        }
        content.addView(previewExisting)
        save = button("Save clip and next") { saveSelection() }
        content.addView(save)
        keep = button("Keep existing clip and next") {
            if (repository.clipFile(words[index].word.surface).isFile && rememberSelection()) advance()
        }
        content.addView(keep)
        finish = button("Finish verse") {
            if (rememberSelection() && reviewed.size == words.size) {
                discardConfirmed = true
                onComplete()
            } else {
                refresh()
                toast("Review and save the changed clip before finishing.")
            }
        }
        content.addView(finish)
        refresh()
        return content
    }

    private fun toggleRecord() {
        if (working) return
        if (!recording) {
            if (!hasPermission()) return
            if (samples.isNotEmpty()) {
                AlertDialog.Builder(activity).setMessage("Discard this verse take and record again?")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> beginRecording() }.show()
            } else beginRecording()
        } else {
            recording = false
            try {
                val file = recorder.stop()
                working = true
                refresh()
                io.execute {
                    try {
                        val decoded = AndroidAudioDecoder().decode(file)
                        val suggested = VerseAudio.suggestBoundaries(decoded, spokenWordCount)
                        activity.runOnUiThread {
                            if (!closed) {
                                samples = decoded
                                starts = suggested.dropLast(1).toIntArray()
                                ends = suggested.drop(1).toIntArray()
                                reviewed.clear()
                                index = 0
                                waveform.showLevels(RecordingWaveform.peaks(decoded))
                                working = false
                                showSelection()
                                refresh()
                            }
                        }
                    } catch (e: Exception) {
                        activity.runOnUiThread { if (!closed) { working = false; toast(e.message ?: "Unable to decode take."); refresh() } }
                    }
                }
            } catch (e: Exception) {
                toast(e.message ?: "Unable to stop recording.")
                refresh()
            }
        }
    }

    private fun beginRecording() {
        try {
            player.stop()
            draft?.delete()
            val file = repository.newVerseDraft()
            draft = file
            recorder.start(file)
            recording = true
            samples = FloatArray(0)
            starts = IntArray(0)
            ends = IntArray(0)
            reviewed.clear()
            waveform.showLevels(emptyList())
            refresh()
        } catch (e: Exception) {
            toast(e.message ?: "Unable to start recording.")
            refresh()
        }
    }

    private fun refresh() {
        val ready = samples.isNotEmpty() && !working && !recording
        record.text = if (recording) "Stop recording" else if (samples.isEmpty()) "Record verse" else "Record again"
        record.isEnabled = !working
        select.isEnabled = ready
        start.isEnabled = ready
        end.isEnabled = ready
        startSeek.isEnabled = ready
        endSeek.isEnabled = ready
        previewButton.isEnabled = ready
        previewWhole.isEnabled = ready
        previewOrdered.isEnabled = ready
        previewExisting.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        save.isEnabled = ready
        keep.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        finish.isEnabled = ready && reviewed.size == words.size
        status.text = if (recording) "Recording…" else "${reviewed.size} / ${words.size} clips reviewed"
        updating = true
        select.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            words.mapIndexed { i, word ->
                val delta = i - word.spokenIndex
                "${if (i in reviewed) "✓ " else ""}${i + 1}. " +
                    "${word.word.gloss.ifBlank { word.word.surface }} — ${word.word.surface} " +
                    "(spoken ${word.spokenIndex + 1}, ${if (delta > 0) "+" else ""}$delta)"
            })
        select.setSelection(index)
        updating = false
    }

    private fun showSelection() {
        val spokenIndex = words[index].spokenIndex
        start.setText(seconds(starts[spokenIndex]))
        end.setText(seconds(ends[spokenIndex]))
        startSeek.progress = (starts[spokenIndex].toLong() * 10000 / samples.size).toInt()
        endSeek.progress = (ends[spokenIndex].toLong() * 10000 / samples.size).toInt()
        keep.isEnabled = repository.clipFile(words[index].word.surface).isFile
        previewExisting.isEnabled = keep.isEnabled
    }

    private fun selection(): Pair<Int, Int>? {
        val from = start.text.toString().toDoubleOrNull()
        val to = end.text.toString().toDoubleOrNull()
        if (from == null || to == null || !from.isFinite() || !to.isFinite() ||
            from < 0 || to > (samples.size + 1.0) / VerseAudio.SAMPLE_RATE || from >= to
        ) {
            toast("Enter valid start and end times within the recording.")
            return null
        }
        val first = (from * VerseAudio.SAMPLE_RATE).roundToInt()
        val last = (to * VerseAudio.SAMPLE_RATE).roundToInt().coerceAtMost(samples.size)
        if (last <= first) {
            toast("The selection is too short.")
            return null
        }
        return first to last
    }

    private fun previewSelection() {
        val (first, last) = selection() ?: return
        previewSamples(first, last)
    }

    private fun rememberSelection(): Boolean {
        val (first, last) = selection() ?: return false
        val spokenIndex = words[index].spokenIndex
        if (starts[spokenIndex] != first || ends[spokenIndex] != last) reviewed.remove(index)
        starts[spokenIndex] = first
        ends[spokenIndex] = last
        return true
    }

    private fun previewCardOrder() {
        if (samples.isEmpty() || working || recording || !rememberSelection()) return
        try {
            val length = words.sumOf { (ends[it.spokenIndex] - starts[it.spokenIndex]).toLong() }
            require(length <= VerseAudio.SAMPLE_RATE * 600L) { "The card-order preview is too long." }
            val parts = words.map { VerseAudio.slice(samples, starts[it.spokenIndex], ends[it.spokenIndex]) }
            val joined = FloatArray(length.toInt())
            var offset = 0
            parts.forEach { part ->
                part.copyInto(joined, offset)
                offset += part.size
            }
            player.stop()
            preview?.delete()
            val file = repository.newVersePreview()
            preview = file
            file.writeBytes(VerseAudio.encodeWav(joined))
            player.play(listOf(file))
            refresh()
        } catch (e: Exception) {
            toast(e.message ?: "Unable to preview card order.")
        }
    }

    private fun previewSamples(first: Int, last: Int) {
        try {
            player.stop()
            preview?.delete()
            val file = repository.newVersePreview()
            preview = file
            file.writeBytes(VerseAudio.encodeWav(VerseAudio.slice(samples, first, last)))
            player.play(listOf(file))
        } catch (e: Exception) {
            toast(e.message ?: "Unable to preview selection.")
        }
    }

    private fun saveSelection() {
        val (first, last) = selection() ?: return
        val word = words[index].word
        fun save() {
            val candidate = repository.recordingCandidateFile(word.surface)
            if (candidate.exists()) {
                toast("A pending word take must be reviewed before replacing this clip.")
                return
            }
            working = true
            player.stop()
            refresh()
            val part = VerseAudio.slice(samples, first, last)
            io.execute {
                var written = false
                try {
                    check(candidate.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
                    candidate.writeBytes(VerseAudio.encodeWav(part))
                    written = true
                    repository.commitRecording(word.surface, "audio/wav")
                    activity.runOnUiThread {
                        if (!closed) {
                            val spokenIndex = words[index].spokenIndex
                            starts[spokenIndex] = first
                            ends[spokenIndex] = last
                            working = false
                            advance()
                        }
                    }
                } catch (e: Exception) {
                    // Keep the candidate if committing failed so that the take can be recovered.
                    if (!written) candidate.delete()
                    activity.runOnUiThread {
                        if (!closed) { working = false; toast(e.message ?: "Unable to save clip."); refresh() }
                    }
                }
            }
        }
        if (repository.clipFile(word.surface).isFile) {
            AlertDialog.Builder(activity).setMessage(
                "Replace the existing recording for ${word.surface}? This form is shared across occurrences and verses."
            )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Replace") { _, _ -> save() }.show()
        } else save()
    }

    private fun advance() {
        reviewed.add(index)
        index = words.indices.firstOrNull { it !in reviewed } ?: index
        refresh()
        showSelection()
    }

    fun close(): Boolean {
        if (working) return false
        if (!discardConfirmed && (recording || samples.isNotEmpty())) {
            AlertDialog.Builder(activity)
                .setMessage("Discard the verse take? Saved clips will be kept.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Discard") { _, _ ->
                    discardConfirmed = true
                    onBack()
                }.show()
            return false
        }
        dispose()
        return true
    }

    fun dispose() {
        closed = true
        if (recording) {
            recording = false
            runCatching { recorder.stop() }
        }
        player.stop()
        io.execute {
            draft?.delete()
            preview?.delete()
        }
        io.shutdown()
    }

    fun stopForBackground() {
        player.stop()
        if (!recording) return
        recording = false
        runCatching { recorder.stop() }
        draft?.delete()
        draft = null
        toast("Recording cancelled because the app left the foreground.")
        refresh()
    }

    private fun seconds(sample: Int) = String.format(Locale.US, "%.6f", sample.toDouble() / VerseAudio.SAMPLE_RATE)
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
