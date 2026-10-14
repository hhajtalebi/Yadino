package ir.yadino.domain

import ir.yadino.domain.planning.DayLayout
import ir.yadino.domain.planning.DayLayout.Block
import org.junit.Assert.assertEquals
import org.junit.Test

class DayLayoutTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    private fun layout(vararg blocks: Block<String>) = DayLayout.layout(blocks.toList()).associateBy { it.key }

    @Test
    fun separateBlocksUseTheFullWidth() {
        val p = layout(Block("a", h(9), h(10)), Block("b", h(10), h(11)), Block("c", h(14), h(15)))
        p.values.forEach { assertEquals(1, it.columns); assertEquals(0, it.column) }
    }

    @Test
    fun overlappingBlocksShareTheWidth() {
        val p = layout(Block("a", h(9), h(10)), Block("b", h(9, 30), h(10, 30)))
        assertEquals(2, p.getValue("a").columns)
        assertEquals(2, p.getValue("b").columns)
        assertEquals(0, p.getValue("a").column)
        assertEquals(1, p.getValue("b").column)
    }

    @Test
    fun chainedOverlapsFormOneGroupAndReuseFreeColumns() {
        // a overlaps b, b overlaps c, a and c only touch: c reuses a's column
        val p = layout(Block("a", h(9), h(10)), Block("b", h(9, 30), h(10, 30)), Block("c", h(10), h(11)), Block("d", h(12), h(13)))
        assertEquals(listOf(2, 2, 2), listOf("a", "b", "c").map { p.getValue(it).columns })
        assertEquals(listOf(0, 1, 0), listOf("a", "b", "c").map { p.getValue(it).column })
        assertEquals(1, p.getValue("d").columns)
    }

    @Test
    fun threeAtOnceAndLongerBlocksFirst() {
        val p = layout(Block("short", h(8), h(8, 30)), Block("long", h(8), h(12)), Block("mid", h(8, 15), h(9)))
        assertEquals(listOf(3, 3, 3), p.values.map { it.columns })
        assertEquals(0, p.getValue("long").column)
        assertEquals(1, p.getValue("short").column)
        assertEquals(2, p.getValue("mid").column)
        // a block after "short" and "mid" ended takes their free column, still inside the group of "long"
        val q = layout(Block("long", h(8), h(12)), Block("short", h(8), h(8, 30)), Block("later", h(10), h(11)))
        assertEquals(1, q.getValue("later").column)
        assertEquals(2, q.getValue("later").columns)
    }

    @Test
    fun snapping() {
        assertEquals(0, DayLayout.snap(7))
        assertEquals(15, DayLayout.snap(8))
        assertEquals(h(9, 15), DayLayout.snap(h(9, 8)))
        assertEquals(-15, DayLayout.snap(-8))
        assertEquals(h(10), DayLayout.snap(h(10, 14), 30))
    }

    @Test
    fun movingKeepsTheDurationInsideTheDay() {
        assertEquals(h(9, 15) to h(10, 15), DayLayout.move(h(9), h(10), 20))
        assertEquals(h(8, 30) to h(9, 30), DayLayout.move(h(9), h(10), -28))
        // tiny movements do nothing, even for times that are not on the grid
        assertEquals(h(9, 7) to h(10), DayLayout.move(h(9, 7), h(10), 5))
        // clamped at both ends of the day
        assertEquals(0 to 60, DayLayout.move(h(0, 30), h(1, 30), -100))
        assertEquals(h(22, 59) to DayLayout.LAST_MINUTE, DayLayout.move(h(22), h(23), 200))
    }

    @Test
    fun resizingSnapsAndKeepsAMinimumLength() {
        assertEquals(h(10, 15), DayLayout.resize(h(9), h(10), 22))
        assertEquals(h(9, 15), DayLayout.resize(h(9), h(10), -100))
        assertEquals(DayLayout.LAST_MINUTE, DayLayout.resize(h(23), h(23, 30), 120))
        assertEquals(h(10), DayLayout.resize(h(9), h(10), 6))
        assertEquals(DayLayout.LAST_MINUTE, DayLayout.resize(h(23, 50), h(23, 55), -30))
    }
}
