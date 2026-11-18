package io.github.danzuep.bibleaudioproducer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.danzuep.bibleaudioproducer.R
import io.github.danzuep.bibleaudioproducer.workbench.RecordingCard

data class RecordingCardRow(
    val card: RecordingCard,
    val recorded: Boolean,
    val recording: Boolean
)

class RecordingCardAdapter(
    private val onRecord: (RecordingCard) -> Unit,
    private val onPlay: (RecordingCard) -> Unit
) : ListAdapter<RecordingCardRow, RecordingCardAdapter.RecordingCardViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingCardViewHolder =
        RecordingCardViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_audio_workbench_card, parent, false)
        )

    override fun onBindViewHolder(holder: RecordingCardViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class RecordingCardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val lemma = view.findViewById<TextView>(R.id.audio_workbench_lemma)
        private val gloss = view.findViewById<TextView>(R.id.audio_workbench_gloss)
        private val occurrences = view.findViewById<TextView>(R.id.audio_workbench_occurrences)
        private val record = view.findViewById<Button>(R.id.audio_workbench_record)
        private val play = view.findViewById<Button>(R.id.audio_workbench_play)

        fun bind(row: RecordingCardRow) {
            lemma.text = row.card.lemma
            gloss.text = row.card.gloss
            occurrences.text = itemView.context.getString(
                R.string.audio_workbench_occurrences,
                row.card.occurrences
            )
            record.text = itemView.context.getString(
                when {
                    row.recording -> R.string.audio_workbench_stop
                    row.recorded -> R.string.audio_workbench_record_again
                    else -> R.string.audio_workbench_record
                }
            )
            play.visibility = if (row.recorded && !row.recording) View.VISIBLE else View.GONE
            record.setOnClickListener { onRecord(row.card) }
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
