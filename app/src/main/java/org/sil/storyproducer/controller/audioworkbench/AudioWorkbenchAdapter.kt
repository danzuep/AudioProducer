package org.sil.storyproducer.controller.audioworkbench

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.sil.storyproducer.R

data class AudioWorkbenchRow(
    val card: AudioWorkbenchCard,
    val recorded: Boolean,
    val recording: Boolean
)

class AudioWorkbenchAdapter(
    private val onRecord: (AudioWorkbenchCard) -> Unit,
    private val onPlay: (AudioWorkbenchCard) -> Unit
) : ListAdapter<AudioWorkbenchRow, AudioWorkbenchAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_audio_workbench_card, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val lemma = view.findViewById<TextView>(R.id.audio_workbench_lemma)
        private val gloss = view.findViewById<TextView>(R.id.audio_workbench_gloss)
        private val occurrences = view.findViewById<TextView>(R.id.audio_workbench_occurrences)
        private val record = view.findViewById<Button>(R.id.audio_workbench_record)
        private val play = view.findViewById<Button>(R.id.audio_workbench_play)

        fun bind(row: AudioWorkbenchRow) {
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
        private val DIFF = object : DiffUtil.ItemCallback<AudioWorkbenchRow>() {
            override fun areItemsTheSame(oldItem: AudioWorkbenchRow, newItem: AudioWorkbenchRow): Boolean =
                oldItem.card.lemma == newItem.card.lemma

            override fun areContentsTheSame(oldItem: AudioWorkbenchRow, newItem: AudioWorkbenchRow): Boolean =
                oldItem == newItem
        }
    }
}
