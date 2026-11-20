package io.github.danzuep.bibleaudioproducer.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import android.view.DragEvent
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioDecoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
import io.github.danzuep.bibleaudioproducer.audio.GreekTransliterator
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
    private var audioOrder = words.map { it.originalIndex }
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
    private lateinit var deckScroll: HorizontalScrollView
    private lateinit var next: Button
    private lateinit var guide: TextView
    private lateinit var customGuide: EditText
    private var guideMode = 0
    private lateinit var start: EditText
    private lateinit var end: EditText
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var status: TextView
    private lateinit var waveform: VerseSliceWaveform
    private lateinit var save: Button
    private lateinit var keep: Button
    private lateinit var finish: Button
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
        content.addView(TextView(activity).apply {
            text = "Record in current card order. The guide keeps take order after recording; drag boundaries and review every clip."
        })
        if (words.map { it.word.surface }.toSet().size < words.size) {
            content.addView(TextView(activity).apply {
                text = "Repeated Greek forms share one saved clip; replacing it changes every occurrence."
            })
        }
        content.addView(TextView(activity).apply { text = "Recording guide" })
        guide = TextView(activity)
        customGuide = EditText(activity).apply {
            hint = "Enter your recording guide"
            visibility = View.GONE
        }
        content.addView(Spinner(activity).apply {
            contentDescription = "Recording guide language"
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf("English", "Chinese", "Original", "Transliteration", "Custom"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    guideMode = position
                    updateGuide()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
        content.addView(guide)
        content.addView(customGuide)
        record = button("Record verse") { toggleRecord() }
        content.addView(record)
        waveform = VerseSliceWaveform(activity).apply {
            contentDescription = "Spoken waveform. Tap a slice or drag a boundary."
            onSelect = { spoken ->
                val next = words.indexOfFirst { audioIndex(it) == spoken }
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
        deckScroll = object : HorizontalScrollView(activity) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                deck.setPaddingRelative(0, 0, (w - dp(220) - dp(8)).coerceAtLeast(0), 0)
            }
        }.apply {
            isFillViewport = false
            addView(deck)
        }
        content.addView(deckScroll)
        next = button("Next") { selectCard(index + 1) }
        content.addView(next)
        content.addView(TextView(activity).apply {
            text = "Next only changes selection. Save or keep each clip before finishing."
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
            freezeAudioOrder()
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
        val spoken = audioIndex(words[index])
        start.isEnabled = ready && spoken > 0
        end.isEnabled = ready && spoken < words.lastIndex
        startSeek.isEnabled = start.isEnabled
        endSeek.isEnabled = end.isEnabled
        next.isEnabled = !working && !recording && index < words.lastIndex
        previewExisting.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        save.isEnabled = ready
        keep.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        finish.isEnabled = ready && reviewed.size == words.size
        status.text = if (recording) "Recording…" else "${reviewed.size} / ${words.size} clips reviewed"
        renderDeck(ready)
        updateGuide()
        waveform.show(samples, boundaries?.snapshot().orEmpty(), audioIndex(words[index]))
    }

    private fun selectCard(next: Int) {
        if (working || recording || next !in words.indices || next == index) return
        if (boundaries != null && !rememberSelection()) return
        index = next
        showSelection()
        refresh()
        scrollToSelection()
    }

    private fun moveCard(from: Int, to: Int) {
        if (working || recording || from == to || from !in words.indices || to !in words.indices) return
        if (boundaries != null && !rememberSelection()) return
        val selected = words[index].originalIndex
        words.add(to, words.removeAt(from))
        index = words.indexOfFirst { it.originalIndex == selected }
        onReorder(words.map { it.originalIndex })
        showSelection()
        refresh()
        scrollToSelection()
    }

    private fun renderDeck(ready: Boolean) {
        deck.removeAllViews()
        words.forEachIndexed { position, word ->
            val spoken = audioIndex(word)
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
                        startDragAndDrop(null, View.DragShadowBuilder(this), word.originalIndex, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        startDrag(null, View.DragShadowBuilder(this), word.originalIndex, 0)
                    }
                }
                setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> event.localState is Int
                        DragEvent.ACTION_DROP -> {
                            val from = words.indexOfFirst { it.originalIndex == (event.localState as? Int) }
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
            body.addView(TextView(activity).apply {
                text = "⠿  ${position + 1}-${word.originalIndex + 1}" +
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
            addControl("▶", "Play ${targetLabel(word)} slice", ready) {
                if (position == index || selectCardForPreview(position)) previewSelection()
            }
            body.addView(controls)
            card.addView(body)
            deck.addView(card, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun showSelection() {
        val spokenIndex = audioIndex(words[index])
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
        val spoken = audioIndex(words[index])
        previewSamples(points.start(spoken), points.end(spoken))
        refresh()
    }

    private fun rememberSelection(): Boolean {
        val (first, last) = selection() ?: return false
        val spokenIndex = audioIndex(words[index])
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
        val first = points.start(audioIndex(words[index]))
        val last = points.end(audioIndex(words[index]))
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
        reviewed.add(audioIndex(words[index]))
        if (index < words.lastIndex) index++
        refresh()
        showSelection()
        scrollToSelection()
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
    private fun freezeAudioOrder() {
        audioOrder = words.map { it.originalIndex }
    }

    private fun audioIndex(word: VerseRecordingWord) =
        if (recording || samples.isNotEmpty()) audioOrder.indexOf(word.originalIndex)
        else words.indexOfFirst { it.originalIndex == word.originalIndex }
    private fun selectCardForPreview(position: Int): Boolean {
        selectCard(position)
        return index == position
    }

    private fun scrollToSelection() {
        deckScroll.post {
            val card = deck.getChildAt(index) ?: return@post
            if (card.isLaidOut) {
                alignCard(card)
            } else {
                card.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        view: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        view.removeOnLayoutChangeListener(this)
                        if (deck.indexOfChild(view) == index) alignCard(view)
                    }
                })
            }
        }
    }

    private fun alignCard(card: View) {
        val offset = if (deck.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            card.right - deckScroll.width
        } else card.left
        deckScroll.smoothScrollTo(offset, 0)
    }

    private fun updateGuide() {
        guide.visibility = if (guideMode == 4) View.GONE else View.VISIBLE
        customGuide.visibility = if (guideMode == 4) View.VISIBLE else View.GONE
        if (guideMode == 4) return
        val guideWords = if (recording || samples.isNotEmpty()) {
            audioOrder.map { original -> words.first { it.originalIndex == original } }
        } else words
        guide.text = guideWords.joinToString(" · ") {
            val text = when (guideMode) {
                1 -> it.word.mandarin
                2 -> it.word.surface
                3 -> GreekTransliterator.transliterate(it.word.surface)
                else -> it.word.gloss
            }
            text.ifBlank { "[translation needed]" }
        }
    }
    private fun targetLabel(word: VerseRecordingWord) =
        word.word.gloss.ifBlank { word.word.mandarin.ifBlank { "[translation needed]" } }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
