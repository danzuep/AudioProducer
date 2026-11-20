package io.github.danzuep.bibleaudioproducer.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.view.DragEvent
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioDecoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.audio.VerseBoundaries
import com.google.android.material.card.MaterialCardView
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
    words: List<VerseRecordingWord>,
    private val hasPermission: () -> Boolean,
    private val onReorder: (List<Int>) -> Unit,
    private val onBack: () -> Unit,
    private val onComplete: () -> Unit
) {
    private val io = Executors.newSingleThreadExecutor()
    private val words = words.toMutableList()
    private val recorder = AndroidAudioRecorder(activity)
    private val player = AndroidAudioPlayer { toast("Unable to play this audio.") }
    private var draft: File? = null
    private var preview: File? = null
    private var samples = FloatArray(0)
    private var boundaries: VerseBoundaries? = null
    private val reviewed = mutableSetOf<Int>()
    private var recording = false
    private var working = false
    private var closed = false
    private var discardConfirmed = false
    private var index = 0
    private lateinit var record: Button
    private lateinit var deck: LinearLayout
    private lateinit var start: EditText
    private lateinit var end: EditText
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var status: TextView
    private lateinit var waveform: VerseSliceWaveform
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
            text = "Record translations in source-word order. Drag the waveform boundaries; review every clip."
        })
        content.addView(button("Source-order gloss guide") {
            AlertDialog.Builder(activity).setTitle("Source-order gloss guide")
                .setMessage(words.sortedBy { it.spokenIndex }.joinToString(" · ") { targetLabel(it) })
                .setPositiveButton(android.R.string.ok, null).show()
        })
        record = button("Record verse") { toggleRecord() }
        content.addView(record)
        waveform = VerseSliceWaveform(activity).apply {
            contentDescription = "Spoken waveform. Tap a slice or drag a boundary."
            onSelect = { spoken ->
                val next = words.indexOfFirst { it.spokenIndex == spoken }
                if (next >= 0) selectCard(next)
            }
            onBoundary = { boundary, sample ->
                if (rememberSelection()) {
                    val changed = boundaries?.move(boundary, sample).orEmpty()
                    reviewed.removeAll(changed)
                    showSelection()
                    refresh()
                }
            }
        }
        content.addView(waveform, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        status = TextView(activity)
        content.addView(status)
        content.addView(TextView(activity).apply {
            text = "Card order · Hold and drag a card to reorder. Tap to review."
        })
        deck = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(HorizontalScrollView(activity).apply {
            isFillViewport = false
            addView(deck)
        })
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
                        val suggested = VerseAudio.suggestBoundaries(decoded, words.size)
                        activity.runOnUiThread {
                            if (!closed) {
                                samples = decoded
                                boundaries = VerseBoundaries(suggested)
                                reviewed.clear()
                                index = 0
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
            boundaries = null
            reviewed.clear()
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
        waveform.isEnabled = ready
        val spoken = words[index].spokenIndex
        start.isEnabled = ready && spoken > 0
        end.isEnabled = ready && spoken < words.lastIndex
        startSeek.isEnabled = start.isEnabled
        endSeek.isEnabled = end.isEnabled
        previewButton.isEnabled = ready
        previewWhole.isEnabled = ready
        previewOrdered.isEnabled = ready
        previewExisting.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        save.isEnabled = ready
        keep.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        finish.isEnabled = ready && reviewed.size == words.size
        status.text = if (recording) "Recording…" else "${reviewed.size} / ${words.size} clips reviewed"
        renderDeck(ready)
        waveform.show(samples, boundaries?.snapshot().orEmpty(), words[index].spokenIndex)
    }

    private fun selectCard(next: Int) {
        if (working || recording || next == index) return
        if (boundaries != null && !rememberSelection()) return
        index = next
        showSelection()
        refresh()
    }

    private fun moveCard(from: Int, to: Int) {
        if (working || recording || from == to || from !in words.indices || to !in words.indices) return
        if (boundaries != null && !rememberSelection()) return
        val selected = words[index].spokenIndex
        words.add(to, words.removeAt(from))
        index = words.indexOfFirst { it.spokenIndex == selected }
        onReorder(words.map { it.spokenIndex })
        showSelection()
        refresh()
    }

    private fun renderDeck(ready: Boolean) {
        deck.removeAllViews()
        words.forEachIndexed { position, word ->
            val spoken = word.spokenIndex
            val card = MaterialCardView(activity).apply {
                radius = dp(12).toFloat()
                strokeWidth = dp(if (position == index) 3 else 1)
                strokeColor = VerseSliceWaveform.color(spoken)
                setCardBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                contentDescription = "${targetLabel(word)}, ${word.word.surface}, spoken ${spoken + 1}"
                setOnClickListener { selectCard(position) }
                setOnLongClickListener {
                    if (working || recording) false
                    else if (android.os.Build.VERSION.SDK_INT >= 24) {
                        startDragAndDrop(null, View.DragShadowBuilder(this), spoken, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        startDrag(null, View.DragShadowBuilder(this), spoken, 0)
                    }
                }
                setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> event.localState is Int
                        DragEvent.ACTION_DROP -> {
                            val from = words.indexOfFirst { it.spokenIndex == (event.localState as? Int) }
                            moveCard(from, position)
                            true
                        }
                        else -> true
                    }
                }
            }
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val delta = position - spoken
            body.addView(TextView(activity).apply {
                text = "⠿  ${position + 1}  [${if (delta > 0) "+" else ""}$delta]" +
                    if (spoken in reviewed) "  ✓" else ""
                setTextColor(VerseSliceWaveform.color(spoken))
            })
            body.addView(TextView(activity).apply {
                text = targetLabel(word)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            body.addView(TextView(activity).apply {
                text = word.word.surface
                textSize = 16f
            })
            body.addView(TextView(activity).apply {
                val points = boundaries
                text = if (points == null) "No take" else {
                    "${(points.end(spoken) - points.start(spoken)) * 1000L / VerseAudio.SAMPLE_RATE} ms · spoken ${spoken + 1}"
                }
            })
            val controls = LinearLayout(activity)
            fun addControl(label: String, description: String, enabled: Boolean, action: () -> Unit) {
                controls.addView(Button(activity).apply {
                    text = label
                    contentDescription = description
                    isEnabled = enabled && !working && !recording
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            addControl("‹", "Move ${targetLabel(word)} earlier", position > 0) { moveCard(position, position - 1) }
            addControl("▶", "Play ${targetLabel(word)} slice", ready) {
                selectCard(position)
                previewSelection()
            }
            addControl("›", "Move ${targetLabel(word)} later", position < words.lastIndex) { moveCard(position, position + 1) }
            body.addView(controls)
            card.addView(body)
            deck.addView(card, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun showSelection() {
        val spokenIndex = words[index].spokenIndex
        val points = boundaries ?: return
        start.setText(seconds(points.start(spokenIndex)))
        end.setText(seconds(points.end(spokenIndex)))
        startSeek.progress = (points.start(spokenIndex).toLong() * 10000 / samples.size).toInt()
        endSeek.progress = (points.end(spokenIndex).toLong() * 10000 / samples.size).toInt()
        waveform.show(samples, points.snapshot(), spokenIndex)
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
        if (!rememberSelection()) return
        val points = checkNotNull(boundaries)
        val spoken = words[index].spokenIndex
        previewSamples(points.start(spoken), points.end(spoken))
        refresh()
    }

    private fun rememberSelection(): Boolean {
        val (first, last) = selection() ?: return false
        val spokenIndex = words[index].spokenIndex
        val points = boundaries ?: return false
        val changed = try {
            points.setRange(spokenIndex, first, last)
        } catch (exception: IllegalArgumentException) {
            toast(exception.message ?: "Invalid boundary.")
            return false
        }
        reviewed.removeAll(changed)
        return true
    }

    private fun previewCardOrder() {
        if (samples.isEmpty() || working || recording || !rememberSelection()) return
        try {
            val points = checkNotNull(boundaries)
            val length = words.sumOf { (points.end(it.spokenIndex) - points.start(it.spokenIndex)).toLong() }
            require(length <= VerseAudio.SAMPLE_RATE * 600L) { "The card-order preview is too long." }
            val parts = words.map { VerseAudio.slice(samples, points.start(it.spokenIndex), points.end(it.spokenIndex)) }
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
        if (!rememberSelection()) return
        val points = checkNotNull(boundaries)
        val first = points.start(words[index].spokenIndex)
        val last = points.end(words[index].spokenIndex)
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
        reviewed.add(words[index].spokenIndex)
        index = words.indices.firstOrNull { words[it].spokenIndex !in reviewed } ?: index
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
    private fun targetLabel(word: VerseRecordingWord) =
        word.word.gloss.ifBlank { word.word.mandarin.ifBlank { "[translation needed]" } }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
