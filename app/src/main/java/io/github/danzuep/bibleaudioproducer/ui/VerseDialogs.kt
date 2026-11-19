package io.github.danzuep.bibleaudioproducer.ui

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.TypedValue
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.NeuralStrength
import io.github.danzuep.bibleaudioproducer.audio.VerseExportFormat
import io.github.danzuep.bibleaudioproducer.audio.GreekTransliterator
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import io.github.danzuep.bibleaudioproducer.workbench.AudioVerse
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord
import io.github.danzuep.bibleaudioproducer.workbench.VerseWorkflow
import android.os.Build
import kotlin.math.max

internal class VerseDialogs(
    private val activity: AppCompatActivity,
    private val currentRepository: () -> AudioProjectRepository,
    private val actions: Actions
) {
    private val repository get() = currentRepository()

    interface Actions {
        val state: AudioProjectState
        val busy: Boolean
        val speechFailed: Boolean
        fun playVerse(verse: AudioVerse, smoothOverride: Boolean? = null)
        fun isPlayingVerse(verse: AudioVerse): Boolean
        fun playChapter(chapter: Int, verses: List<AudioVerse>)
        fun isPlayingChapter(chapter: Int): Boolean
        fun downloadVerse(verse: AudioVerse, strength: NeuralStrength?, format: VerseExportFormat)
        fun saveVerseState(reference: String, order: List<Int>?, unspoken: Set<String>, neuralOff: Set<String>)
        fun recordWord(word: VerseWord)
        fun isPlaying(card: RecordingCard): Boolean
        fun playRecording(card: RecordingCard)
        fun speakGreek(text: String)
        fun stopPlayback()
    }

    private val refreshers = mutableListOf<() -> Unit>()

    fun refreshControls() = refreshers.toList().forEach { it() }

    fun showChapters(bookVerses: List<AudioVerse>) {
        val chapters = bookVerses.map { it.chapter }.distinct()
        AlertDialog.Builder(activity)
            .setTitle(R.string.audio_choose_chapter)
            .setItems(chapters.map { activity.getString(R.string.audio_chapter, it) }.toTypedArray()) { _, index ->
                showVerses(bookVerses, chapters[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVerseActions(verse: AudioVerse) {
        val options = arrayOf(
            activity.getString(R.string.audio_download_verse),
            activity.getString(R.string.audio_download_neural),
            activity.getString(R.string.audio_download_neural_strong)
        )
        AlertDialog.Builder(activity)
            .setTitle(verse.reference)
            .setItems(options) { _, index ->
                val strength = when (index) {
                    1 -> NeuralStrength.STANDARD
                    2 -> NeuralStrength.STRONG
                    else -> null
                }
                chooseFormat(verse, strength)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseFormat(verse: AudioVerse, strength: NeuralStrength?) {
        val formats = VerseExportFormat.entries.filter { Build.VERSION.SDK_INT >= it.minSdk }
        AlertDialog.Builder(activity)
            .setTitle(R.string.audio_choose_format)
            .setItems(formats.map { activity.getString(it.label) }.toTypedArray()) { _, index ->
                actions.downloadVerse(verse, strength, formats[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVerses(bookVerses: List<AudioVerse>, chapter: Int) {
        val verses = bookVerses.filter { it.chapter == chapter }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val title = TextView(activity).apply {
            text = activity.getString(R.string.audio_chapter, chapter)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val chapterPlay = roundButton(R.color.workbench_primary)
        val close = Button(activity).apply {
            text = "←"
            contentDescription = activity.getString(R.string.audio_back)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(56)))
        header.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(chapterPlay)
        root.addView(header)

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val verseButtons = mutableListOf<Pair<AudioVerse, MaterialButton>>()
        val wordLabels = mutableListOf<Pair<VerseWord, TextView>>()
        verses.forEach { verse ->
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val wordText = VerseFlowLayout(activity).apply {
                isClickable = true
                isFocusable = true
                contentDescription = verse.words.joinToString(" ") { it.surface }
                setOnClickListener {
                    actions.stopPlayback()
                    chapterDialog?.dismiss()
                    openVerse(verse)
                }
                verse.words.forEach { word ->
                    val greek = greekLabel(word, 18f)
                    wordLabels += word to greek
                    addView(wordStack(word, greek).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) })
                }
            }
            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = verse.reference
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(activity, R.color.workbench_secondary))
                })
                addView(wordText)
            }
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val play = roundButton(R.color.workbench_primary).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                cornerRadius = dp(22)
                textSize = 18f
                setOnClickListener { actions.playVerse(verse, null) }
            }
            verseButtons += verse to play
            row.addView(play, LinearLayout.LayoutParams(dp(48), dp(48)))
            val download = Button(activity).apply {
                text = "↓"
                contentDescription = activity.getString(R.string.audio_download_verse)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setOnClickListener { showVerseActions(verse) }
            }
            row.addView(download, LinearLayout.LayoutParams(dp(44), dp(48)))
            rows.addView(row)
        }
        val dialog = AlertDialog.Builder(activity).setView(root).create()
        chapterDialog = dialog
        chapterPlay.setOnClickListener { actions.playChapter(chapter, verses) }
        close.setOnClickListener { dialog.dismiss() }
        val refresh = {
            val state = actions.state
            val chapterRecorded = verses.any { verse ->
                VerseWorkflow.spokenWords(
                    verse.words, state.rules, state.seedOrders[verse.reference], state.unspoken,
                    state.wordOrders[verse.reference]
                ).any { repository.clipFile(it.surface).isFile }
            }
            val chapterPlayable = chapterRecorded || !actions.speechFailed
            chapterPlay.isEnabled = chapterPlayable
            chapterPlay.alpha = if (chapterPlayable) 1f else 0.45f
            chapterPlay.setText(
                if (actions.isPlayingChapter(chapter)) R.string.audio_stop_symbol else R.string.audio_play_symbol
            )
            chapterPlay.contentDescription = activity.getString(
                if (actions.isPlayingChapter(chapter)) R.string.audio_stop_chapter else R.string.audio_play_chapter
            )
            verseButtons.forEach { (verse, button) ->
                val recorded = VerseWorkflow.spokenWords(
                    verse.words, state.rules, state.seedOrders[verse.reference], state.unspoken,
                    state.wordOrders[verse.reference]
                ).any { repository.clipFile(it.surface).isFile }
                val playable = recorded || !actions.speechFailed
                button.isEnabled = playable
                button.alpha = if (playable) 1f else 0.45f
                button.setText(
                    if (actions.isPlayingVerse(verse)) R.string.audio_stop_symbol else R.string.audio_play_symbol
                )
                button.contentDescription = activity.getString(
                    if (actions.isPlayingVerse(verse)) R.string.audio_stop_word else R.string.audio_play_verse,
                    verse.reference
                )
            }
            wordLabels.forEach { (word, label) ->
                label.setTextColor(wordColor(word.disabled(state.unspoken), repository.clipFile(word.surface).isFile))
            }
        }
        refreshers.clear()
        refreshers.add(refresh)
        refresh()
        dialog.setOnDismissListener {
            refreshers.remove(refresh)
            if (chapterDialog === dialog) chapterDialog = null
            actions.stopPlayback()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun openVerse(verse: AudioVerse) {
        if (actions.busy) return
        var order = VerseWorkflow.orderedWordIndices(
            verse.words, actions.state.rules, actions.state.wordOrders[verse.reference],
            actions.state.seedOrders[verse.reference]
        ).toMutableList()
        val unspoken = actions.state.unspoken.toMutableSet()
        var changed = false
        var neuralChanged = false
        val neuralOff = actions.state.neuralOff.toMutableSet()
        var smoothOverride: Boolean? =
            if (actions.state.neuralAudio) verse.reference !in neuralOff else null
        val recordedStates = mutableMapOf<String, Boolean>()
        lateinit var renderWords: () -> Unit

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val reference = TextView(activity).apply {
            text = verse.reference
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val play = roundButton(R.color.workbench_primary).apply {
            setOnClickListener { actions.playVerse(verse, smoothOverride) }
        }
        val more = Button(activity).apply {
            id = R.id.audio_verse_more
            text = "⋮"
            contentDescription = activity.getString(R.string.audio_download_verse)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setOnClickListener { showVerseActions(verse) }
        }
        val close = Button(activity).apply {
            id = R.id.audio_verse_close
            text = "←"
            contentDescription = activity.getString(R.string.audio_back)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(56)))
        header.addView(reference, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(play)
        header.addView(more, LinearLayout.LayoutParams(dp(48), dp(56)))
        root.addView(header)
        val hint = TextView(activity).apply {
            text = activity.getString(R.string.audio_verse_drag_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(hint)
        val controls = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val reset = MaterialButton(activity).apply {
            setText(R.string.audio_reset_order)
            setOnClickListener {
                val original = verse.words.indices.toList()
                if (order != original) {
                    order = original.toMutableList()
                    changed = true
                    renderWords()
                }
            }
        }
        val neural = MaterialCheckBox(activity).apply {
            text = activity.getString(R.string.audio_verse_neural_toggle)
            isChecked = actions.state.neuralAudio && verse.reference !in neuralOff
            isEnabled = actions.state.neuralAudio
            setOnCheckedChangeListener { _, checked ->
                if (checked) neuralOff.remove(verse.reference) else neuralOff.add(verse.reference)
                neuralChanged = true
                smoothOverride = checked
            }
        }
        controls.addView(reset)
        controls.addView(neural, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls)

        lateinit var flow: VerseFlowLayout
        lateinit var scroll: ScrollView
        renderWords = {
            val oldScroll = scroll.scrollY
            flow.removeAllViews()
            order.forEachIndexed { orderIndex, wordIndex ->
                val word = verse.words[wordIndex]
                val recorded = repository.clipFile(word.surface).isFile
                recordedStates[word.surface] = recorded
                val token = wordToken(
                    word,
                    word.disabled(unspoken),
                    recorded,
                    28f
                )
                token.setOnClickListener {
                    showWordDetails(word, word.disabled(unspoken)) { disabled ->
                        word.withDisabled(unspoken, disabled)
                        changed = true
                        renderWords()
                    }
                }
                val drag = object : View.OnTouchListener {
                    private var downX = 0f
                    private var downY = 0f
                    private var touching = false
                    private var dragging = false

                    fun lift(): Boolean {
                        if (!touching) return false
                        dragging = true
                        token.translationZ = dp(8).toFloat()
                        token.alpha = 0.85f
                        flow.requestDisallowInterceptTouchEvent(true)
                        return true
                    }

                    override fun onTouch(view: View, event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = event.rawX
                                downY = event.rawY
                                touching = true
                            }
                            MotionEvent.ACTION_MOVE -> if (dragging) {
                                view.translationX = event.rawX - downX
                                view.translationY = event.rawY - downY
                                return true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                touching = false
                                if (!dragging) return false
                                dragging = false
                                view.isPressed = false
                                view.alpha = 1f
                                view.translationX = 0f
                                view.translationY = 0f
                                view.translationZ = 0f
                                flow.requestDisallowInterceptTouchEvent(false)
                                if (event.actionMasked == MotionEvent.ACTION_UP) {
                                    val bounds = android.graphics.Rect()
                                    scroll.getGlobalVisibleRect(bounds)
                                    val location = IntArray(2)
                                    val windowLocation = IntArray(2)
                                    scroll.getLocationOnScreen(location)
                                    scroll.getLocationInWindow(windowLocation)
                                    bounds.offset(location[0] - windowLocation[0], location[1] - windowLocation[1])
                                    val target = if (bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                                        flow.indexAt(event.rawX, event.rawY)
                                    } else -1
                                    if (target >= 0 && target != orderIndex) {
                                        order.add(target, order.removeAt(orderIndex))
                                        changed = true
                                        renderWords()
                                    }
                                }
                                return true
                            }
                        }
                        return false
                    }
                }
                token.setOnTouchListener(drag)
                token.setOnLongClickListener { drag.lift() }
                flow.addView(token)
            }
            scroll.post { scroll.scrollTo(0, oldScroll) }
        }

        flow = VerseFlowLayout(activity)
        scroll = ScrollView(activity).apply {
            setFillViewport(true)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(flow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        renderWords()
        refreshers.clear()
        refreshers.add {
            val recordingStateChanged = order.map { verse.words[it].surface }.distinct().any { surface ->
                val recorded = repository.clipFile(surface).isFile
                recordedStates.put(surface, recorded)?.let { it != recorded } == true
            }
            if (recordingStateChanged) renderWords()
            val recorded = VerseWorkflow.spokenWords(
                verse.words, actions.state.rules, actions.state.seedOrders[verse.reference], unspoken, order
            ).any { repository.clipFile(it.surface).isFile }
            val playable = recorded || !actions.speechFailed
            play.isEnabled = playable
            play.setText(if (actions.isPlayingVerse(verse)) R.string.audio_stop_symbol else R.string.audio_play_symbol)
            play.contentDescription = if (actions.isPlayingVerse(verse)) {
                activity.getString(R.string.audio_stop_word, verse.reference)
            } else {
                activity.getString(R.string.audio_play_verse)
            }
            play.alpha = if (playable) 1f else 0.45f
        }
        refreshControls()

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        verseDialog = dialog
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            refreshers.clear()
            if (changed || neuralChanged) {
                actions.saveVerseState(
                    verse.reference,
                    if (changed) order.toList() else actions.state.wordOrders[verse.reference],
                    unspoken,
                    neuralOff
                )
            }
            actions.stopPlayback()
            verseDialog = null
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var verseDialog: AlertDialog? = null

    private var chapterDialog: AlertDialog? = null

    private fun showWordDetails(word: VerseWord, disabled: Boolean, onToggleDisabled: (Boolean) -> Unit) {
        val card = RecordingCard("", word.surface, word.gloss, 0)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(16))
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val greek = greekLabel(word, 28f).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val record = roundButton(if (repository.clipFile(word.surface).isFile) {
            R.color.workbench_muted
        } else {
            R.color.workbench_record
        }).apply {
            setOnClickListener { actions.recordWord(word) }
        }
        val play = roundButton(R.color.workbench_primary).apply {
            setOnClickListener {
                if (repository.clipFile(word.surface).isFile) actions.playRecording(card)
                else actions.speakGreek(word.surface)
            }
        }
        val close = Button(activity).apply {
            text = "←"
            contentDescription = activity.getString(R.string.audio_back)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(56)))
        header.addView(wordStack(word, greek), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(record)
        header.addView(play, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            leftMargin = dp(4)
        })
        root.addView(header)
        lateinit var dialog: AlertDialog
        root.addView(MaterialButton(activity).apply {
            setText(if (disabled) R.string.audio_word_restore else R.string.audio_word_disable)
            setOnClickListener {
                onToggleDisabled(!disabled)
                dialog.dismiss()
            }
        })
        root.addView(MaterialButton(activity).apply {
            setText(R.string.audio_speak_greek)
            setOnClickListener { actions.speakGreek(word.surface) }
        })

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), dp(4), 0)
        }
        listOf(
            R.string.audio_word_field_reference to word.reference,
            R.string.audio_word_field_surface to word.surface,
            R.string.audio_word_field_lemma to word.lemma,
            R.string.audio_word_field_gloss to word.gloss,
            R.string.audio_word_field_pos to word.pos,
            R.string.audio_word_field_type to word.type,
            R.string.audio_word_field_role to word.role,
            R.string.audio_word_field_case to word.grammaticalCase,
            R.string.audio_word_field_mood to word.mood,
            R.string.audio_word_field_mandarin to word.mandarin,
            R.string.audio_word_field_morphology to word.morphology
        ).forEach { (label, value) ->
            content.addView(TextView(activity).apply {
                text = "${activity.getString(label)}: ${value.ifBlank { "—" }}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        dialog = AlertDialog.Builder(activity).setView(root).create()
        close.setOnClickListener { dialog.dismiss() }
        val refresh = {
            val recorded = repository.clipFile(word.surface).isFile
            record.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    activity,
                    if (recorded) R.color.workbench_muted else R.color.workbench_record
                )
            )
            record.setText(R.string.audio_record_symbol)
            record.contentDescription = activity.getString(R.string.audio_word_record, word.surface)
            play.isEnabled = recorded || actions.isPlaying(card) || !actions.speechFailed
            play.alpha = if (play.isEnabled) 1f else 0.4f
            play.setText(if (actions.isPlaying(card)) R.string.audio_stop_symbol else R.string.audio_play_symbol)
            play.contentDescription = activity.getString(
                if (actions.isPlaying(card)) R.string.audio_stop_word else R.string.audio_word_play, word.surface
            )
        }
        refreshers.add(refresh)
        refresh()
        dialog.setOnDismissListener {
            refreshers.remove(refresh)
            actions.stopPlayback()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun wordToken(word: VerseWord, unspoken: Boolean, recorded: Boolean, fontSize: Float): View {
        val token = FrameLayout(activity).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isLongClickable = true
            contentDescription = activity.getString(
                if (unspoken) R.string.audio_word_spoken else R.string.audio_word_unspoken,
                word.surface
            )
        }
        val greek = greekLabel(word, fontSize).apply { setTextColor(wordColor(unspoken, recorded)) }
        token.addView(wordStack(word, greek), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        return token
    }

    private fun greekLabel(word: VerseWord, fontSize: Float) = TextView(activity).apply {
        text = word.surface
        gravity = Gravity.CENTER
        textSize = fontSize
    }

    private fun wordColor(unspoken: Boolean, recorded: Boolean) = ContextCompat.getColor(activity, when {
        unspoken -> R.color.workbench_unspoken
        recorded -> R.color.workbench_recorded
        else -> R.color.workbench_primary_dark
    })

    private fun wordStack(word: VerseWord, greek: TextView) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val gloss = listOf(word.gloss, word.mandarin).filter(String::isNotBlank).joinToString(" · ")
        fun annotation(label: String) = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            maxWidth = dp(140)
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.workbench_secondary))
        }
        addView(annotation(GreekTransliterator.transliterate(word.surface)))
        addView(greek)
        if (gloss.isNotEmpty()) addView(annotation(gloss))
    }

    private fun roundButton(color: Int): MaterialButton {
        val size = dp(56)
        return MaterialButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            setPadding(0, 0, 0, 0)
            cornerRadius = size / 2
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, color))
            setTextColor(ContextCompat.getColor(activity, android.R.color.white))
            textSize = 22f
            setText(R.string.audio_play_symbol)
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}

private class VerseFlowLayout(
    context: android.content.Context
) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = width - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            measureChild(child, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), heightMeasureSpec)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (x > 0 && x + childWidth > available) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            x += childWidth
            rowHeight = max(rowHeight, childHeight)
        }
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(y + rowHeight + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = right - left - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (x > paddingLeft && x + childWidth > paddingLeft + available) {
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
            }
            val childLeft = x + params.leftMargin
            val childTop = y + params.topMargin
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
            x += childWidth
            rowHeight = max(rowHeight, childHeight)
        }
    }

    fun indexAt(rawX: Float, rawY: Float): Int {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = rawX - location[0]
        val y = rawY - location[1]
        var closestIndex = -1
        var closestDistance = Float.POSITIVE_INFINITY
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (y < child.top || y > child.bottom) continue
            val distance = max(child.left - x, x - child.right).coerceAtLeast(0f)
            if (distance < closestDistance) {
                closestIndex = index
                closestDistance = distance
            }
        }
        return closestIndex
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: android.util.AttributeSet): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean = params is MarginLayoutParams
}
