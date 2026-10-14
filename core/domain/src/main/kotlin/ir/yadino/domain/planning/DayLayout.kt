package ir.yadino.domain.planning

import kotlin.math.abs

/**
 * Geometry of the day timeline: overlapping blocks are placed side by side, and blocks are
 * moved or resized with snapping. Minutes are minutes of the day (0 = 00:00); a block's end
 * is exclusive and may be 1440 (midnight) for display, while stored end times are at most
 * [LAST_MINUTE] (23:59).
 */
object DayLayout {
    const val LAST_MINUTE = 24 * 60 - 1
    const val DAY_MINUTES = 24 * 60
    const val STEP = 15

    data class Block<K>(val key: K, val start: Int, val end: Int)

    /** A block in column [column] of [columns] equally wide columns. */
    data class Placement<K>(val key: K, val start: Int, val end: Int, val column: Int, val columns: Int)

    /**
     * Assigns blocks to columns like calendar apps do: blocks that overlap (directly or
     * through a chain of overlaps) form a group that shares the width; inside a group each
     * block takes the first column that is free at its start. Touching blocks (one ends when
     * the next starts) do not overlap.
     */
    fun <K> layout(blocks: List<Block<K>>): List<Placement<K>> {
        val sorted = blocks.sortedWith(compareBy<Block<K>> { it.start }.thenByDescending { it.end })
        val result = ArrayList<Placement<K>>(blocks.size)
        val group = ArrayList<Pair<Block<K>, Int>>()
        val columnEnds = ArrayList<Int>()
        var groupEnd = Int.MIN_VALUE
        fun flush() {
            val columns = columnEnds.size
            group.forEach { (b, column) -> result += Placement(b.key, b.start, b.end, column, columns) }
            group.clear()
            columnEnds.clear()
            groupEnd = Int.MIN_VALUE
        }
        for (b in sorted) {
            if (group.isNotEmpty() && b.start >= groupEnd) flush()
            var column = columnEnds.indexOfFirst { it <= b.start }
            if (column < 0) {
                columnEnds += b.end
                column = columnEnds.lastIndex
            } else {
                columnEnds[column] = b.end
            }
            group += b to column
            groupEnd = maxOf(groupEnd, b.end)
        }
        if (group.isNotEmpty()) flush()
        return result
    }

    /** Rounds [minute] to the nearest multiple of [step]. */
    fun snap(minute: Int, step: Int = STEP): Int = Math.floorDiv(minute + step / 2, step) * step

    /**
     * Start and end after dragging a block by [delta] minutes: the start snaps to [step],
     * the duration stays the same and the block stays inside the day. Movements shorter
     * than half a step leave the block where it was.
     */
    fun move(start: Int, end: Int, delta: Int, step: Int = STEP): Pair<Int, Int> {
        if (abs(delta) < (step + 1) / 2) return start to end
        val duration = end - start
        val newStart = snap(start + delta, step).coerceIn(0, maxOf(0, LAST_MINUTE - duration))
        return newStart to newStart + duration
    }

    /**
     * End after dragging the bottom edge of a block by [delta] minutes: snapped to [step],
     * at least one step after the start and at most 23:59.
     */
    fun resize(start: Int, end: Int, delta: Int, step: Int = STEP): Int {
        if (abs(delta) < (step + 1) / 2) return end
        return snap(end + delta, step).coerceIn(minOf(start + step, LAST_MINUTE), LAST_MINUTE)
    }
}
