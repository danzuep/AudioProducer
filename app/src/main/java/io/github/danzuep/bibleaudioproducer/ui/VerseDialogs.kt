package io.github.danzuep.bibleaudioproducer.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.TypedValue
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.NeuralStrength
import io.github.danzuep.bibleaudioproducer.audio.VerseExportFormat
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import io.github.danzuep.bibleaudioproducer.workbench.AudioVerse
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import io.github.danzuep.bibleaudioproducer.workbench.VerseWord
import io.github.danzuep.bibleaudioproducer.workbench.VerseWorkflow
import android.os.Build
import kotlin.math.hypot
import kotlin.math.max

internal class VerseDialogs(
    private val activity: AppCompatActivity,
    private val repository: AudioProjectRepository,
    private val actions: Actions
) {
    interface Actions {
        val state: AudioProjectState
        val busy: Boolean
        fun playVerse(verse: AudioVerse)
        fun isPlayingVerse(verse: AudioVerse): Boolean
        fun downloadVerse(verse: AudioVerse, strength: NeuralStrength?, format: VerseExportFormat)
        fun saveOrder(reference: String, order: List<Int>?, unspoken: Set<String>)
        fun toggleRecording(card: RecordingCard)
        fun pressRecording(card: RecordingCard)
        fun releaseRecording(card: RecordingCard)
        fun cancelRecording(card: RecordingCard)
        fun isRecording(card: RecordingCard): Boolean
        fun isPlaying(card: RecordingCard): Boolean
        fun playRecording(card: RecordingCard)
        fun closeRecording()
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
        val state = actions.state
        val labels = verses.map { verse ->
            val words = VerseWorkflow.spokenWords(
                verse.words, state.rules, state.seedOrders[verse.reference], state.unspoken,
                state.wordOrders[verse.reference]
            )
            val missing = words.map { it.lemma }.distinct().count { !repository.clipFile(it).isFile }
            val status = when {
                verse.words.none { !it.proper } -> activity.getString(R.string.audio_verse_no_words)
                missing == 0 -> activity.getString(R.string.audio_verse_ready)
                else -> activity.getString(R.string.audio_verse_missing, missing)
            }
            "${verse.reference} · $status\n${words.map { it.gloss }.filter { it.isNotBlank() && it != "-" }.joinToString(" ")}"
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.audio_chapter, chapter))
            .setItems(labels.toTypedArray()) { _, index -> openVerse(verses[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun openVerse(verse: AudioVerse) {
        if (actions.busy) return
        var order = VerseWorkflow.orderedWordIndices(
            verse.words, actions.state.rules, actions.state.wordOrders[verse.reference],
            actions.state.seedOrders[verse.reference]
        ).toMutableList()
        val unspoken = actions.state.unspoken.toMutableSet()
        var changed = false
        var fontSize = 28f

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
            setOnClickListener { actions.playVerse(verse) }
        }
        val more = Button(activity).apply {
            text = "⋮"
            contentDescription = activity.getString(R.string.audio_download_verse)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setOnClickListener { showVerseActions(verse) }
        }
        val close = Button(activity).apply {
            text = "×"
            contentDescription = activity.getString(android.R.string.cancel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }
        header.addView(reference, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(play)
        header.addView(more, LinearLayout.LayoutParams(dp(48), dp(56)))
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(56)))
        root.addView(header)
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.audio_verse_drag_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        })

        lateinit var flow: VerseFlowLayout
        lateinit var scroll: ScrollView
        fun renderWords() {
            val oldScroll = scroll.scrollY
            flow.removeAllViews()
            order.forEachIndexed { orderIndex, wordIndex ->
                val word = verse.words[wordIndex]
                val token = wordToken(word, word.unspokenKey in unspoken, fontSize)
                token.setOnClickListener { showWordDetails(word) }
                token.setOnLongClickListener {
                    if (word.unspokenKey in unspoken) unspoken.remove(word.unspokenKey)
                    else unspoken.add(word.unspokenKey)
                    changed = true
                    renderWords()
                    true
                }
                token.setOnTouchListener(object : View.OnTouchListener {
                    private var downX = 0f
                    private var downY = 0f
                    private var dragging = false
                    private var pinching = false
                    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

                    override fun onTouch(view: View, event: MotionEvent): Boolean {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            downX = event.rawX
                            downY = event.rawY
                            dragging = false
                            pinching = false
                            return false
                        }
                        if (flow.isScaling) {
                            pinching = true
                            return true
                        }
                        if (pinching) {
                            if (event.actionMasked == MotionEvent.ACTION_UP ||
                                event.actionMasked == MotionEvent.ACTION_CANCEL
                            ) pinching = false
                            return true
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_MOVE -> {
                                if (!dragging && hypot(event.rawX - downX, event.rawY - downY) > touchSlop) {
                                    dragging = true
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                if (dragging) {
                                    view.alpha = 0.65f
                                    return true
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (dragging) {
                                    view.alpha = 1f
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                    val target = flow.indexAt(event.rawX, event.rawY)
                                    if (target >= 0 && target != orderIndex) {
                                        val moved = order.removeAt(orderIndex)
                                        val destination = if (target > orderIndex) target - 1 else target
                                        order.add(destination.coerceIn(0, order.size), moved)
                                        changed = true
                                        renderWords()
                                    }
                                    return true
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                if (dragging) {
                                    view.alpha = 1f
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                    return true
                                }
                            }
                        }
                        return false
                    }
                })
                flow.addView(token)
            }
            scroll.post { scroll.scrollTo(0, oldScroll) }
        }

        flow = VerseFlowLayout(activity) { scale ->
            fontSize = (fontSize * scale).coerceIn(16f, 56f)
            renderWords()
        }
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
            val recorded = VerseWorkflow.spokenWords(
                verse.words, actions.state.rules, actions.state.seedOrders[verse.reference], unspoken, order
            ).any { repository.clipFile(it.lemma).isFile }
            play.isEnabled = recorded
            play.setText(if (actions.isPlayingVerse(verse)) R.string.audio_stop_symbol else R.string.audio_play_symbol)
            play.contentDescription = if (actions.isPlayingVerse(verse)) {
                activity.getString(R.string.audio_stop_word, verse.reference)
            } else {
                activity.getString(R.string.audio_play_verse)
            }
            play.alpha = if (recorded) 1f else 0.45f
        }
        refreshControls()

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        verseDialog = dialog
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            refreshers.clear()
            if (changed) actions.saveOrder(verse.reference, order.toList(), unspoken)
            actions.closeRecording()
            verseDialog = null
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private var verseDialog: AlertDialog? = null

    private fun showWordDetails(word: VerseWord) {
        val card = RecordingCard("", word.lemma, word.gloss, 0)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(12), dp(16))
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val greek = TextView(activity).apply {
            text = word.surface
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val record = roundButton(if (repository.clipFile(word.lemma).isFile) {
            R.color.workbench_muted
        } else {
            R.color.workbench_record
        }).apply {
            setRecordingGesture(
                onPress = { actions.pressRecording(card) },
                onRelease = { actions.releaseRecording(card) },
                onCancel = { actions.cancelRecording(card) },
                onAccessibleClick = { actions.toggleRecording(card) }
            )
        }
        val play = roundButton(R.color.workbench_primary).apply {
            isEnabled = repository.clipFile(word.lemma).isFile
            alpha = if (isEnabled) 1f else 0.4f
            setOnClickListener {
                if (repository.clipFile(word.lemma).isFile) actions.playRecording(card)
                else Toast.makeText(activity, R.string.audio_word_not_recorded, Toast.LENGTH_SHORT).show()
            }
        }
        val close = Button(activity).apply {
            text = "×"
            contentDescription = activity.getString(android.R.string.cancel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }
        header.addView(greek, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(record)
        header.addView(play, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            leftMargin = dp(4)
        })
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(56)))
        root.addView(header)

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
            LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
        ))
        val dialog = AlertDialog.Builder(activity).setView(root).create()
        close.setOnClickListener { dialog.dismiss() }
        val refresh = {
            val recording = actions.isRecording(card)
            val recorded = repository.clipFile(word.lemma).isFile
            record.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    activity,
                    if (recorded) R.color.workbench_muted else R.color.workbench_record
                )
            )
            record.setText(if (recording) R.string.audio_stop_symbol else R.string.audio_record_symbol)
            record.contentDescription = activity.getString(
                if (recording) R.string.audio_recording_active else R.string.audio_word_record, word.surface
            )
            play.isEnabled = recorded || actions.isPlaying(card)
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
            actions.closeRecording()
        }
        dialog.show()
    }

    private fun wordToken(word: VerseWord, unspoken: Boolean, fontSize: Float): View {
        val token = FrameLayout(activity).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isLongClickable = true
            contentDescription = activity.getString(
                if (unspoken) R.string.audio_word_spoken else R.string.audio_word_unspoken,
                word.surface
            )
        }
        val stack = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        if (word.gloss.isNotBlank() && word.gloss != "-") {
            stack.addView(TextView(activity).apply {
                text = word.gloss
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, max(10f, fontSize * 0.45f))
                setTextColor(Color.DKGRAY)
            })
        }
        stack.addView(TextView(activity).apply {
            text = word.surface
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            setTextColor(ContextCompat.getColor(activity, R.color.workbench_primary_dark))
        })
        token.addView(stack, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        if (unspoken) {
            token.addView(TextView(activity).apply {
                text = "∅"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize + 10f)
                setTextColor(ContextCompat.getColor(activity, R.color.workbench_record))
                alpha = 0.8f
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        return token
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
    context: android.content.Context,
    private val onZoom: (Float) -> Unit
) : ViewGroup(context) {
    var isScaling = false
        private set
    private val scaleDetector = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                onZoom(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        return false
    }

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
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) return index
        }
        return -1
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: android.util.AttributeSet): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean = params is MarginLayoutParams
}
