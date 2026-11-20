package io.github.danzuep.bibleaudioproducer.audio

/** Shared boundaries in chronological spoken order. */
class VerseBoundaries(initial: List<Int>) {
    private val points = initial.toMutableList()

    init {
        require(points.size >= 2 && points.first() == 0 &&
            points.zipWithNext().all { (a, b) -> a < b }) { "Invalid verse boundaries." }
    }

    val count: Int get() = points.size - 1
    fun start(index: Int): Int = points[index]
    fun end(index: Int): Int = points[index + 1]
    fun snapshot(): List<Int> = points.toList()

    fun setRange(index: Int, start: Int, end: Int): Set<Int> {
        require(index in 0 until count && start < end &&
            (if (index == 0) start == points.first() else start > points[index - 1]) &&
            (if (index == count - 1) end == points.last() else end < points[index + 2])) {
            "The selection must remain between its neighboring boundaries."
        }
        val changed = mutableSetOf<Int>()
        if (points[index] != start) {
            points[index] = start
            changed.add(index - 1)
            changed.add(index)
        }
        if (points[index + 1] != end) {
            points[index + 1] = end
            changed.add(index)
            changed.add(index + 1)
        }
        return changed
    }

    fun move(boundary: Int, sample: Int): Set<Int> {
        require(boundary in 1 until points.lastIndex) { "The recording endpoints cannot be moved." }
        require(sample > points[boundary - 1] && sample < points[boundary + 1]) {
            "A boundary must stay between its neighboring boundaries."
        }
        if (points[boundary] == sample) return emptySet()
        points[boundary] = sample
        return setOf(boundary - 1, boundary)
    }
}
