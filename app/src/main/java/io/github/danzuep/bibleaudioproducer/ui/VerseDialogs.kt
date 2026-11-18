package io.github.danzuep.bibleaudioproducer.ui

import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.data.AudioProjectRepository
import io.github.danzuep.bibleaudioproducer.workbench.AudioProjectState
import io.github.danzuep.bibleaudioproducer.workbench.AudioVerse
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import io.github.danzuep.bibleaudioproducer.workbench.VerseWorkflow

internal class VerseDialogs(
    private val activity: AppCompatActivity,
    private val repository: AudioProjectRepository,
    private val actions: Actions
) {
    interface Actions {
        val state: AudioProjectState
        val busy: Boolean
        fun playVerse(verse: AudioVerse)
        fun saveOrder(reference: String, order: List<Int>?, unspoken: Set<String>)
        fun toggleRecording(card: RecordingCard)
        fun playRecording(card: RecordingCard)
        fun closeRecording()
    }

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

    private fun showVerses(bookVerses: List<AudioVerse>, chapter: Int) {
        val verses = bookVerses.filter { it.chapter == chapter }
        val state = actions.state
        val labels = verses.map { verse ->
            val words = VerseWorkflow.spokenWords(
                verse.words, state.rules, state.seedOrders[verse.reference], state.unspoken
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
            .setItems(labels.toTypedArray()) { _, index -> showVerseMenu(verses[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVerseMenu(verse: AudioVerse) {
        val labels = arrayOf(
            activity.getString(R.string.audio_play_verse),
            activity.getString(R.string.audio_arrange_verse),
            activity.getString(R.string.audio_record_verse_words)
        )
        AlertDialog.Builder(activity)
            .setTitle(verse.reference)
            .setItems(labels) { _, index ->
                when (index) {
                    0 -> actions.playVerse(verse)
                    1 -> arrangeVerse(verse)
                    2 -> showVerseWords(verse)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun arrangeVerse(verse: AudioVerse) {
        if (actions.busy) return
        val chunks = VerseWorkflow.chunks(verse.words)
        val saved = actions.state.seedOrders[verse.reference]
        val order = (saved?.takeIf {
            it.size == chunks.size && it.toSet() == chunks.indices.toSet()
        } ?: chunks.indices.toList()).toMutableList()
        val unspoken = actions.state.unspoken.toMutableSet()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        lateinit var draw: () -> Unit
        draw = {
            content.removeAllViews()
            order.forEachIndexed { position, chunkIndex ->
                val chunk = chunks[chunkIndex]
                val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                val earlier = Button(activity).apply {
                    text = "←"
                    contentDescription = activity.getString(R.string.audio_move_earlier)
                    isEnabled = position > 0
                    setOnClickListener {
                        order.add(position - 1, order.removeAt(position))
                        draw()
                    }
                }
                val later = Button(activity).apply {
                    text = "→"
                    contentDescription = activity.getString(R.string.audio_move_later)
                    isEnabled = position < order.lastIndex
                    setOnClickListener {
                        order.add(position + 1, order.removeAt(position))
                        draw()
                    }
                }
                val label = TextView(activity).apply {
                    text = "${chunk.words.joinToString(" ") { it.surface }}\n" +
                        chunk.words.joinToString(" ") { it.gloss }
                }
                header.addView(earlier)
                header.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                header.addView(later)
                content.addView(header)
                chunk.words.filterNot { it.proper }.forEach { word ->
                    content.addView(CheckBox(activity).apply {
                        text = activity.getString(R.string.audio_speak_word, word.surface)
                        isChecked = word.unspokenKey !in unspoken
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) unspoken.remove(word.unspokenKey) else unspoken.add(word.unspokenKey)
                        }
                    })
                }
            }
        }
        draw()
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.audio_arrange_title, verse.reference))
            .setMessage(R.string.audio_arrange_description)
            .setView(dialogScroll(content))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.audio_reset_order) { _, _ ->
                actions.saveOrder(verse.reference, null, unspoken)
            }
            .setPositiveButton(R.string.audio_save_order) { _, _ ->
                actions.saveOrder(verse.reference, order.toList(), unspoken)
            }
            .show()
    }

    private fun showVerseWords(verse: AudioVerse) {
        val state = actions.state
        val words = VerseWorkflow.spokenWords(
            verse.words, state.rules, state.seedOrders[verse.reference], state.unspoken
        )
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        for (word in words) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val card = RecordingCard(verse.reference.substringBefore(' '), word.lemma, word.gloss, 0)
            row.addView(TextView(activity).apply {
                text = "${word.surface}\n${word.gloss}"
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(activity).apply {
                text = activity.getString(R.string.audio_record_stop)
                setOnClickListener { actions.toggleRecording(card) }
            })
            row.addView(Button(activity).apply {
                text = activity.getString(R.string.audio_workbench_play)
                setOnClickListener {
                    if (repository.clipFile(word.lemma).isFile) actions.playRecording(card)
                    else Toast.makeText(activity, R.string.audio_word_not_recorded, Toast.LENGTH_SHORT).show()
                }
            })
            content.addView(row)
        }
        AlertDialog.Builder(activity)
            .setTitle(verse.reference)
            .setView(dialogScroll(content))
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { actions.closeRecording() }
            .show()
    }

    private fun dialogScroll(content: View): View {
        val scroll = ScrollView(activity).apply { addView(content) }
        return FrameLayout(activity).apply {
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.4f).toInt()
                )
            )
        }
    }
}
