package io.github.danzuep.bibleaudioproducer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.danzuep.bibleaudioproducer.R

internal class RecordingWaveform @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.workbench_record)
        strokeCap = Paint.Cap.ROUND
    }
    private var levels = emptyList<Float>()

    fun showLevels(values: List<Float>) {
        levels = values.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (levels.isEmpty()) return
        val spacing = width.toFloat() / MAX_LEVELS
        paint.strokeWidth = (spacing * 0.6f).coerceAtLeast(1f)
        levels.forEachIndexed { index, level ->
            val x = width - (levels.size - index - 0.5f) * spacing
            val half = (height * 0.5f * level.coerceIn(0f, 1f)).coerceAtLeast(1f)
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
    }

    companion object {
        const val MAX_LEVELS = 60
    }
}
