package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoLayoutTest {
    @Test fun `linear chain lays out in a straight line`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(Room("a"), Room("b"), Room("c")),
            exits = listOf(Exit("1", "a", Direction.E, "b"), Exit("2", "b", Direction.E, "c")))
        val out = autoTidy(m)
        val byId = out.rooms.associateBy { it.id }
        assertEquals(0f, byId["a"]!!.x, 0.001f)
        assertEquals(GRID_STEP, byId["b"]!!.x, 0.001f)
        assertEquals(2 * GRID_STEP, byId["c"]!!.x, 0.001f)
    }

    @Test fun `cycle does not duplicate positions`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(Room("a"), Room("b"), Room("c")),
            exits = listOf(Exit("1", "a", Direction.E, "b"),
                           Exit("2", "b", Direction.E, "c"),
                           Exit("3", "c", Direction.W, "a")))
        val out = autoTidy(m)
        val positions = out.rooms.map { Pos(it.x, it.y) }
        assertEquals(positions.size, positions.distinct().size)
    }
}