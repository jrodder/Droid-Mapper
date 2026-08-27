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

    @Test fun `tidy places an IN room in the nearest free neighbor slot`() {
        // A is root at origin; N and NE neighbor cells are taken by other rooms,
        // so A's IN room must land on the next spiral slot (E).
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(Room("a"), Room("b"), Room("c"), Room("d")),
            exits = listOf(Exit("1", "a", Direction.N, "b"),
                           Exit("2", "a", Direction.NE, "c"),
                           Exit("3", "a", Direction.IN, "d")))
        val out = autoTidy(m)
        val byId = out.rooms.associateBy { it.id }
        val d = Pos(byId["d"]!!.x, byId["d"]!!.y)
        assertEquals(Pos(GRID_STEP, 0f), d)
    }
}