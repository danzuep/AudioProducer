package io.github.danzuep.bibleaudioproducer.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioDecoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioEncoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.audio.VerseExportFormat
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** A take is private to this editor until the user explicitly saves each mapping. */
internal class VerseTakeEditor(
    private val activity: AppCompatActivity,
    private val repository: AudioProjectRepository,
    private val words: List<VerseWord>,
    private val reference: String,
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
    private var boundaries = mutableListOf<Int>()
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
    private lateinit var previewExisting: Button

    fun view(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: String, action: () -> Unit) = Button(activity).apply {
            text = label
            setOnClickListener { action() }
        }
        content.addView(button("← Back") { if (!working) onBack() })
        content.addView(TextView(activity).apply {
            text = "$reference · Verse clips"
            textSize = 22f
        })
        content.addView(TextView(activity).apply {
            text = "Record the whole verse in the selected order. Review each suggested clip before saving."
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
                if (!updating && boundaries.isNotEmpty()) {
                    index = position
                    showSelection()
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
        previewExisting = button("Preview existing clip") {
            player.play(listOf(repository.clipFile(words[index].surface)))
        }
        content.addView(previewExisting)
        save = button("Save clip and next") { saveSelection() }
        content.addView(save)
        keep = button("Keep existing clip and next") {
            if (repository.clipFile(words[index].surface).isFile) advance()
        }
        content.addView(keep)
        finish = button("Finish verse") {
            if (reviewed.size == words.size) {
                discardConfirmed = true
                onComplete()
            }
        }
        content.addView(finish)
        root.addView(ScrollView(activity).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
        return root
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
                        val suggested = VerseAudio.suggestBoundaries(decoded, words.size)
                        activity.runOnUiThread {
                            if (!closed) {
                                samples = decoded
                                boundaries = suggested.toMutableList()
                                reviewed.clear()
                                index = 0
                                waveform.showLevels(RecordingWaveform.peaks(decoded))
                                working = false
                                refresh()
                                showSelection()
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
            boundaries.clear()
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
        previewExisting.isEnabled = ready && repository.clipFile(words[index].surface).isFile
        save.isEnabled = ready
        keep.isEnabled = ready && repository.clipFile(words[index].surface).isFile
        finish.isEnabled = ready && reviewed.size == words.size
        status.text = if (recording) "Recording…" else "${reviewed.size} / ${words.size} clips reviewed"
        updating = true
        select.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            words.mapIndexed { i, word ->
                "${if (i in reviewed) "✓ " else ""}${i + 1}. ${word.gloss.ifBlank { word.surface }} — ${word.surface} (${word.lemma})"
            })
        select.setSelection(index)
        updating = false
    }

    private fun showSelection() {
        start.setText(seconds(boundaries[index]))
        end.setText(seconds(boundaries[index + 1]))
        startSeek.progress = (boundaries[index].toLong() * 10000 / samples.size).toInt()
        endSeek.progress = (boundaries[index + 1].toLong() * 10000 / samples.size).toInt()
        keep.isEnabled = repository.clipFile(words[index].surface).isFile
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

    private fun previewSamples(first: Int, last: Int) {
        try {
            player.stop()
            preview?.delete()
            val file = repository.newVersePreview()
            preview = file
            file.writeBytes(VerseAudio.encodeWav(samples.copyOfRange(first, last)))
            player.play(listOf(file))
        } catch (e: Exception) {
            toast(e.message ?: "Unable to preview selection.")
        }
    }

    private fun saveSelection() {
        val (first, last) = selection() ?: return
        val word = words[index]
        fun save() {
            val candidate = repository.recordingCandidateFile(word.surface)
            if (candidate.exists()) {
                toast("A pending word take must be reviewed before replacing this clip.")
                return
            }
            working = true
            player.stop()
            refresh()
            val part = samples.copyOfRange(first, last)
            io.execute {
                var encoded = false
                try {
                    check(candidate.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
                    AndroidAudioEncoder().encode(part, candidate, VerseExportFormat.AAC)
                    encoded = true
                    repository.commitRecording(word.surface)
                    activity.runOnUiThread {
                        if (!closed) {
                            if (index + 2 < boundaries.size && last > boundaries[index] && last < boundaries[index + 2]) {
                                boundaries[index + 1] = last
                            }
                            working = false
                            advance()
                        }
                    }
                } catch (e: Exception) {
                    // Keep the candidate if committing failed so that the take can be recovered.
                    if (!encoded) candidate.delete()
                    activity.runOnUiThread {
                        if (!closed) { working = false; toast(e.message ?: "Unable to save clip."); refresh() }
                    }
                }
            }
        }
        if (repository.clipFile(word.surface).isFile) {
            AlertDialog.Builder(activity).setMessage("Replace the existing recording for ${word.surface}?")
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
