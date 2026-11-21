package io.github.danzuep.bibleaudioproducer.ui

import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioDecoder
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioPlayer
import io.github.danzuep.bibleaudioproducer.audio.AndroidAudioRecorder
import io.github.danzuep.bibleaudioproducer.audio.GreekTransliterator
import io.github.danzuep.bibleaudioproducer.audio.VerseAudio
import io.github.danzuep.bibleaudioproducer.audio.VerseBoundaries
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
    private val onComplete: () -> Unit,
    private val reference: String = "Verse recording"
) {
    private val io = Executors.newSingleThreadExecutor()
    private val words = words.toMutableList()
    private var audioOrder = words.map { it.originalIndex }
    private val recorder = AndroidAudioRecorder(activity)
    private val player = AndroidAudioPlayer {
        playingTake = false
        pausedTake = false
        toast("Unable to play this audio.")
        if (!closed && ::playTake.isInitialized) refresh()
    }
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
    private lateinit var deck: RecyclerView
    private lateinit var cards: WordAdapter
    private lateinit var dragHelper: ItemTouchHelper
    private lateinit var next: Button
    private lateinit var tokens: ChipGroup
    private lateinit var customGuide: EditText
    private var guideMode = 0
    private lateinit var start: EditText
    private lateinit var end: EditText
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var status: TextView
    private lateinit var timing: TextView
    private lateinit var help: View
    private lateinit var playTake: MaterialButton
    private lateinit var nudgeBack: Button
    private lateinit var nudgeForward: Button
    private var playingTake = false
    private var pausedTake = false
    private var reordering = false
    private lateinit var waveform: VerseSliceWaveform
    private lateinit var keep: Button
    private lateinit var finish: Button
    private lateinit var previewExisting: Button

    fun view(): View {
        val context = ContextThemeWrapper(activity, R.style.Theme_ClipEditor)
        val content = LayoutInflater.from(context).inflate(R.layout.activity_clip_editor, null, false)
        val toolbar = content.findViewById<MaterialToolbar>(R.id.clip_toolbar)
        toolbar.title = reference
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { onBack() }
        help = content.findViewById(R.id.clip_help)
        playTake = MaterialButton(context).apply {
            layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
            cornerRadius = dp(24)
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            setIconResource(android.R.drawable.ic_media_play)
            contentDescription = "Play verse take"
            setOnClickListener { toggleTakePlayback() }
        }
        toolbar.menu.add("Play take").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            actionView = playTake
        }
        toolbar.menu.add(0, R.id.clip_help_action, 0, "Info").apply {
            setIcon(android.R.drawable.ic_menu_info_details)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                help.visibility = if (help.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                true
            }
        }
        toolbar.menu.add("Finish verse").setOnMenuItemClickListener {
            finish.performClick()
            true
        }
        tokens = content.findViewById(R.id.clip_tokens)
        customGuide = content.findViewById(R.id.clip_custom_guide)
        content.findViewById<Spinner>(R.id.clip_guide_mode).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf("English", "Chinese", "Original", "Transliteration", "Custom"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    guideMode = position
                    updateGuide()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        record = content.findViewById<Button>(R.id.clip_record).apply {
            setOnClickListener { toggleRecord() }
        }
        waveform = content.findViewById<VerseSliceWaveform>(R.id.clip_waveform).apply {
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
        content.findViewById<View>(R.id.clip_zoom_in).setOnClickListener { waveform.zoomBy(2) }
        content.findViewById<View>(R.id.clip_zoom_out).setOnClickListener { waveform.zoomOut() }
        status = content.findViewById(R.id.clip_progress)
        timing = content.findViewById(R.id.clip_timing)
        deck = content.findViewById(R.id.clip_cards)
        cards = WordAdapter()
        deck.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        deck.adapter = cards
        dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = holder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || working || recording) return false
                return moveCard(from, to)
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, holder)
                cards.notifyDataSetChanged()
            }
        })
        dragHelper.attachToRecyclerView(deck)
        next = content.findViewById<Button>(R.id.clip_next).apply { setOnClickListener { saveSelection() } }
        nudgeBack = content.findViewById<Button>(R.id.clip_nudge_back).apply { setOnClickListener { nudge(-1) } }
        nudgeForward = content.findViewById<Button>(R.id.clip_nudge_forward).apply { setOnClickListener { nudge(1) } }
        start = content.findViewById(R.id.clip_start)
        end = content.findViewById(R.id.clip_end)
        startSeek = content.findViewById(R.id.clip_start_seek)
        endSeek = content.findViewById(R.id.clip_end_seek)
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
        previewExisting = content.findViewById<Button>(R.id.clip_preview_existing).apply {
            setOnClickListener {
                stopPlayback()
                player.play(listOf(repository.clipFile(words[index].word.surface)))
            }
        }
        keep = content.findViewById<Button>(R.id.clip_keep).apply {
            setOnClickListener {
                if (repository.clipFile(words[index].word.surface).isFile && rememberSelection()) advance()
            }
        }
        finish = content.findViewById<Button>(R.id.clip_finish).apply {
            setOnClickListener {
                if (rememberSelection() && reviewed.size == words.size) {
                    discardConfirmed = true
                    onComplete()
                } else {
                    refresh()
                    toast("Review and save the changed clip before finishing.")
                }
            }
        }
        refresh()
        return content
    }

    internal fun loadDemoTake(demoSamples: FloatArray) {
        samples = demoSamples
        boundaries = VerseBoundaries(VerseAudio.suggestBoundaries(samples, words.size))
        audioOrder = words.map { it.originalIndex }
        reviewed.clear()
        index = 0
        showSelection()
        refresh()
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
            stopPlayback()
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
        next.isEnabled = ready
        previewExisting.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        keep.isEnabled = ready && repository.clipFile(words[index].word.surface).isFile
        keep.visibility = if (keep.isEnabled) View.VISIBLE else View.GONE
        finish.isEnabled = ready && reviewed.size == words.size
        nudgeBack.isEnabled = ready && words.size > 1
        nudgeForward.isEnabled = nudgeBack.isEnabled
        val edge = if (spoken < words.lastIndex) "end" else "start"
        nudgeBack.contentDescription = "Move $edge boundary back 10 milliseconds"
        nudgeForward.contentDescription = "Move $edge boundary forward 10 milliseconds"
        playTake.isEnabled = ready
        playTake.setIconResource(if (playingTake && !pausedTake) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        playTake.contentDescription = if (playingTake && !pausedTake) "Pause verse take" else "Play verse take"
        status.text = if (recording) "Recording…" else "${reviewed.size} / ${words.size} reviewed"
        val points = boundaries
        timing.text = if (points == null) "No recording" else {
            val first = points.start(spoken)
            val last = points.end(spoken)
            String.format(Locale.US, "%.2fs – %.2fs (%d ms)", first.toDouble() / VerseAudio.SAMPLE_RATE,
                last.toDouble() / VerseAudio.SAMPLE_RATE, (last - first) * 1000L / VerseAudio.SAMPLE_RATE)
        }
        if (!reordering) cards.notifyDataSetChanged()
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

    private fun moveCard(from: Int, to: Int): Boolean {
        if (working || recording || from == to || from !in words.indices || to !in words.indices) return false
        if (boundaries != null && !rememberSelection()) return false
        val selected = words[index].originalIndex
        words.add(to, words.removeAt(from))
        index = words.indexOfFirst { it.originalIndex == selected }
        onReorder(words.map { it.originalIndex })
        cards.notifyItemMoved(from, to)
        showSelection()
        reordering = true
        refresh()
        reordering = false
        return true
    }

    private inner class WordAdapter : RecyclerView.Adapter<WordAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val card = view as MaterialCardView
            val label = view.findViewById<TextView>(R.id.word_label)
            val greek = view.findViewById<TextView>(R.id.word_greek)
            val position = view.findViewById<TextView>(R.id.word_position)
            val metadata = view.findViewById<Chip>(R.id.word_metadata)
            val play = view.findViewById<Button>(R.id.word_play)
            val drag = view.findViewById<View>(R.id.word_drag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_word_card, parent, false))

        override fun getItemCount() = words.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val word = words[position]
            val spoken = audioIndex(word)
            val color = VerseSliceWaveform.color(spoken)
            val ready = samples.isNotEmpty() && !working && !recording
            holder.card.strokeWidth = dp(if (position == index) 3 else 1)
            holder.card.strokeColor = if (position == index)
                MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorPrimary) else color
            holder.card.contentDescription = "${targetLabel(word)}, ${word.word.surface}, spoken ${spoken + 1}"
            holder.label.text = targetLabel(word)
            holder.greek.text = word.word.surface
            holder.position.text = "Card ${position + 1} • Greek ${word.originalIndex + 1}"
            holder.metadata.chipBackgroundColor = ColorStateList.valueOf(VerseSliceWaveform.color(spoken, 55))
            val points = boundaries
            holder.metadata.text = if (points == null) "No take" else
                "${(points.end(spoken) - points.start(spoken)) * 1000L / VerseAudio.SAMPLE_RATE} ms • spoken ${spoken + 1}"
            holder.play.isEnabled = ready
            holder.play.contentDescription = "Play ${targetLabel(word)} slice"
            holder.card.setOnClickListener {
                val current = holder.bindingAdapterPosition
                if (current != RecyclerView.NO_POSITION) selectCard(current)
            }
            holder.play.setOnClickListener {
                val current = holder.bindingAdapterPosition
                if (current != RecyclerView.NO_POSITION && (current == index || selectCardForPreview(current))) previewSelection()
            }
            holder.drag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !working && !recording) {
                    dragHelper.startDrag(holder)
                }
                false
            }
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
            stopPlayback()
            preview?.delete()
            val file = repository.newVersePreview()
            preview = file
            file.writeBytes(VerseAudio.encodeWav(VerseAudio.slice(samples, first, last)))
            player.play(listOf(file))
        } catch (e: Exception) {
            toast(e.message ?: "Unable to preview selection.")
        }
    }

    private fun toggleTakePlayback() {
        if (playingTake) {
            if (pausedTake) {
                if (player.resume()) pausedTake = false
            } else if (player.pause()) {
                pausedTake = true
            }
        } else {
            try {
                stopPlayback()
                preview?.delete()
                val file = repository.newVersePreview()
                preview = file
                file.writeBytes(VerseAudio.encodeWav(samples))
                playingTake = true
                player.play(listOf(file)) {
                    if (!closed) {
                        playingTake = false
                        pausedTake = false
                        refresh()
                    }
                }
            } catch (e: Exception) {
                playingTake = false
                toast(e.message ?: "Unable to play the verse take.")
            }
        }
        refresh()
    }

    private fun stopPlayback() {
        player.stop()
        playingTake = false
        pausedTake = false
    }

    private fun nudge(direction: Int) {
        if (!rememberSelection()) return
        val points = boundaries ?: return
        val spoken = audioIndex(words[index])
        val boundary = if (spoken < words.lastIndex) spoken + 1 else spoken
        val snapshot = points.snapshot()
        val target = (snapshot[boundary] + direction * VerseAudio.SAMPLE_RATE / 100)
            .coerceIn(snapshot[boundary - 1] + 1, snapshot[boundary + 1] - 1)
        reviewed.removeAll(points.move(boundary, target))
        showSelection()
        refresh()
    }

    private fun saveSelection() {
        if (!rememberSelection()) return
        refresh()
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
            stopPlayback()
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
        if (closed) return
        closed = true
        if (recording) {
            recording = false
            runCatching { recorder.stop() }
        }
        stopPlayback()
        io.execute {
            draft?.delete()
            preview?.delete()
        }
        io.shutdown()
    }

    fun stopForBackground() {
        val wasPlayingTake = playingTake
        stopPlayback()
        if (!recording) {
            if (wasPlayingTake && !closed) refresh()
            return
        }
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
        deck.smoothScrollToPosition(index)
    }

    private fun updateGuide() {
        tokens.visibility = if (guideMode == 4) View.GONE else View.VISIBLE
        customGuide.visibility = if (guideMode == 4) View.VISIBLE else View.GONE
        if (guideMode == 4) return
        val guideWords = if (recording || samples.isNotEmpty()) {
            audioOrder.map { original -> words.first { it.originalIndex == original } }
        } else words
        tokens.removeAllViews()
        guideWords.forEach { word ->
            val spoken = audioIndex(word)
            val isActive = word.originalIndex == words[index].originalIndex
            val label = when (guideMode) {
                1 -> word.word.mandarin
                2 -> word.word.surface
                3 -> GreekTransliterator.transliterate(word.word.surface)
                else -> word.word.gloss
            }
            val chip = Chip(tokens.context).apply {
                text = label.ifBlank { "[translation needed]" }
                isClickable = true
                isCheckable = false
                val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
                val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
                val onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
                val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
                chipBackgroundColor = ColorStateList.valueOf(
                    if (isActive) primary else if (spoken in reviewed)
                        MaterialColors.layer(surface, VerseSliceWaveform.color(spoken), 0.25f) else surface
                )
                chipStrokeColor = ColorStateList.valueOf(if (isActive) primary else VerseSliceWaveform.color(spoken))
                chipStrokeWidth = dp(if (isActive) 2 else 1).toFloat()
                setTextColor(if (isActive) onPrimary else onSurface)
                contentDescription = "${text}, ${if (isActive) "selected" else if (spoken in reviewed) "reviewed" else "unreviewed"}"
                setOnClickListener {
                    val position = words.indexOfFirst { it.originalIndex == word.originalIndex }
                    selectCard(position)
                }
            }
            tokens.addView(chip)
        }
    }
    private fun targetLabel(word: VerseRecordingWord) =
        word.word.gloss.ifBlank { word.word.mandarin.ifBlank { "[translation needed]" } }
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
