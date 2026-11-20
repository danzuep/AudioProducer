package io.github.danzuep.bibleaudioproducer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

internal class VerseSliceWaveform(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var samples = FloatArray(0)
    private var peaks = FloatArray(0)
    private var boundaries = emptyList<Int>()
    private var selected = 0
    private var dragging = -1
    var onSelect: (Int) -> Unit = {}
    var onBoundary: (Int, Int) -> Unit = { _, _ -> }

    fun show(samples: FloatArray, boundaries: List<Int>, selected: Int) {
        if (this.samples !== samples) {
            peaks = if (samples.isEmpty()) FloatArray(0) else FloatArray(minOf(1024, samples.size)) { bucket ->
                val first = (bucket.toLong() * samples.size / minOf(1024, samples.size)).toInt()
                val last = ((bucket + 1L) * samples.size / minOf(1024, samples.size)).toInt()
                var peak = 0f
                for (i in first until last) peak = maxOf(peak, abs(samples[i]))
                peak
            }
        }
        this.samples = samples
        this.boundaries = boundaries
        this.selected = selected
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(239, 244, 250))
        if (samples.isEmpty() || boundaries.size < 2) return
        val scale = width.toFloat() / samples.size
        for (i in 0 until boundaries.lastIndex) {
            paint.color = color(i, if (i == selected) 90 else 42)
            canvas.drawRect(boundaries[i] * scale, 0f, boundaries[i + 1] * scale, height.toFloat(), paint)
        }
        paint.color = Color.rgb(38, 63, 91)
        paint.strokeWidth = 1f
        for (x in 0 until width) {
            val start = (x.toLong() * peaks.size / width).toInt()
            val end = ((x + 1L) * peaks.size / width).toInt().coerceAtLeast(start + 1).coerceAtMost(peaks.size)
            var peak = 0f
            for (i in start until end) peak = maxOf(peak, peaks[i])
            val half = (peak.coerceAtMost(1f) * height * 0.43f).coerceAtLeast(1f)
            canvas.drawLine(x.toFloat(), height / 2f - half, x.toFloat(), height / 2f + half, paint)
        }
        for (i in 1 until boundaries.lastIndex) {
            val x = boundaries[i] * scale
            paint.color = if (i == selected || i == selected + 1) Color.rgb(28, 73, 139) else Color.rgb(136, 153, 170)
            paint.strokeWidth = if (i == selected || i == selected + 1) 3f else 1f
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            if (i == selected || i == selected + 1) canvas.drawCircle(x, height / 2f, 6f * resources.displayMetrics.density, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || samples.isEmpty() || boundaries.size < 2 || width == 0) return false
        val sample = (event.x.coerceIn(0f, width.toFloat()) / width * samples.size).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val radius = 24 * resources.displayMetrics.density
                dragging = (1 until boundaries.lastIndex).minByOrNull {
                    abs(boundaries[it].toFloat() / samples.size * width - event.x)
                }?.takeIf { abs(boundaries[it].toFloat() / samples.size * width - event.x) <= radius } ?: -1
                parent.requestDisallowInterceptTouchEvent(true)
                if (dragging < 0) {
                    val segment = (0 until boundaries.lastIndex).firstOrNull {
                        sample >= boundaries[it] && sample < boundaries[it + 1]
                    } ?: boundaries.lastIndex - 1
                    onSelect(segment)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (dragging > 0) {
                    val low = boundaries[dragging - 1] + 1
                    val high = boundaries[dragging + 1] - 1
                    val target = sample.coerceIn(low, high)
                    val radius = 80
                    val crossing = (maxOf(low, target - radius)..minOf(high, target + radius))
                        .filter { it > 0 && it < samples.size &&
                            (samples[it - 1] <= 0f && samples[it] >= 0f ||
                                samples[it - 1] >= 0f && samples[it] <= 0f) }
                        .minByOrNull { abs(it - target) } ?: target
                    onBoundary(dragging, crossing)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    dragging = -1
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        fun color(index: Int, alpha: Int = 255): Int {
            val colors = intArrayOf(0x3569B1, 0x008776, 0xA65728, 0x8754A4, 0xA83859, 0x5D7924)
            return (alpha shl 24) or colors[index % colors.size]
        }
    }
}
