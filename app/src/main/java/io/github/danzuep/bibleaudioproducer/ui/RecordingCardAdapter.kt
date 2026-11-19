package io.github.danzuep.bibleaudioproducer.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.audio.GreekTransliterator
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard
import androidx.core.content.ContextCompat

data class RecordingCardRow(
    val card: RecordingCard,
    val recorded: Boolean,
    val recording: Boolean,
    val playing: Boolean,
    val grammar: String = "",
    val mandarin: String = ""
)

class RecordingCardAdapter(
    private val onPress: (RecordingCard) -> Unit,
    private val onRelease: (RecordingCard) -> Unit,
    private val onCancel: (RecordingCard) -> Unit,
    private val onToggle: (RecordingCard) -> Unit,
    private val onPlay: (RecordingCard) -> Unit
) : ListAdapter<RecordingCardRow, RecordingCardAdapter.RecordingCardViewHolder>(DIFF) {
    private var recyclerView: RecyclerView? = null
    private val levels = mutableListOf<Float>()

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun clearWaveform() = levels.clear()

    fun addLevel(lemma: String, level: Float): List<Float> {
        levels.add(level)
        if (levels.size > RecordingWaveform.MAX_LEVELS) levels.removeAt(0)
        val index = currentList.indexOfFirst { it.card.lemma == lemma && it.recording }
        val holder = recyclerView?.findViewHolderForAdapterPosition(index) as? RecordingCardViewHolder
        holder?.showWaveform()
        return levels.toList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingCardViewHolder =
        RecordingCardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_audio_workbench_card, parent, false)
        )

    override fun onBindViewHolder(holder: RecordingCardViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class RecordingCardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val lemma = view.findViewById<TextView>(R.id.audio_workbench_lemma)
        private val transliteration = view.findViewById<TextView>(R.id.audio_workbench_transliteration)
        private val gloss = view.findViewById<TextView>(R.id.audio_workbench_gloss)
        private val grammar = view.findViewById<TextView>(R.id.audio_workbench_grammar)
        private val occurrences = view.findViewById<TextView>(R.id.audio_workbench_occurrences)
        private val record = view.findViewById<Button>(R.id.audio_workbench_record)
        private val play = view.findViewById<Button>(R.id.audio_workbench_play)
        private val waveform = view.findViewById<RecordingWaveform>(R.id.audio_workbench_waveform)

        fun showWaveform() = waveform.showLevels(levels)

        fun bind(row: RecordingCardRow) {
            lemma.text = row.card.lemma
            transliteration.text = GreekTransliterator.transliterate(row.card.lemma)
            gloss.text = listOf(row.card.gloss, row.mandarin).filter(String::isNotBlank).joinToString(" · ")
            grammar.text = row.grammar
            occurrences.text = itemView.context.getString(
                R.string.audio_workbench_occurrences,
                row.card.occurrences
            )
            record.setText(if (row.recording) R.string.audio_stop_symbol else R.string.audio_record_symbol)
            record.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    itemView.context,
                    if (row.recorded) R.color.workbench_muted else R.color.workbench_record
                )
            )
            record.contentDescription = itemView.context.getString(
                if (row.recording) R.string.audio_recording_active else R.string.audio_hold_record, row.card.lemma
            )
            waveform.visibility = if (row.recording) View.VISIBLE else View.GONE
            if (row.recording) showWaveform()
            play.visibility = if (row.recorded && !row.recording) View.VISIBLE else View.GONE
            play.setText(if (row.playing) R.string.audio_stop_symbol else R.string.audio_play_symbol)
            play.contentDescription = itemView.context.getString(
                if (row.playing) R.string.audio_stop_word else R.string.audio_play_word, row.card.lemma
            )
            record.setRecordingGesture(
                onPress = { onPress(row.card) },
                onRelease = { onRelease(row.card) },
                onCancel = { onCancel(row.card) },
                onAccessibleClick = { onToggle(row.card) }
            )
            play.setOnClickListener { onPlay(row.card) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecordingCardRow>() {
            override fun areItemsTheSame(oldItem: RecordingCardRow, newItem: RecordingCardRow): Boolean =
                oldItem.card.lemma == newItem.card.lemma

            override fun areContentsTheSame(oldItem: RecordingCardRow, newItem: RecordingCardRow): Boolean =
                oldItem == newItem
        }
    }
}
