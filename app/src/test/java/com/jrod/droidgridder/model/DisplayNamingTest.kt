package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNamingTest {
    private fun room(id: String, name: String) = Room(id = id, name = name)

    @Test fun `duplicate names are numbered in list order, unique names are not`() {
        val rooms = listOf(
            room("a", "Maze"), room("b", "Dead End"), room("c", "Maze"),
            room("d", "Maze"), room("e", "Dead End"), room("f", "")
        )
        val names = displayNames(rooms)
        assertEquals("Maze (1)", names["a"])
        assertEquals("Maze (2)", names["c"])
        assertEquals("Maze (3)", names["d"])
        assertEquals("Dead End (1)", names["b"])
        assertEquals("Dead End (2)", names["e"])
        assertEquals("", names["f"])
    }

    @Test fun `names numbered by list rank are stable when appended to`() {
        val two = listOf(room("a", "Maze"), room("b", "Maze"))
        val three = two + room("c", "Maze")
        assertEquals(displayNames(two), displayNames(three).filterKeys { it != "c" })
        assertEquals("Maze (3)", displayNames(three)["c"])
    }

    @Test fun `edge labels are In-Out only, UP and DOWN draw dashed instead`() {
        assertNull("UP is drawn dashed, not labeled", edgeLabel(Direction.UP))
        assertNull("DOWN is drawn dashed, not labeled", edgeLabel(Direction.DOWN))
        assertEquals("In", edgeLabel(Direction.IN))
        assertEquals("Out", edgeLabel(Direction.OUT))
        // compass directions carry no label — the bearing geometry is the label
        for (d in listOf(Direction.N, Direction.S, Direction.E, Direction.W,
                         Direction.NE, Direction.NW, Direction.SE, Direction.SW)) {
            assertNull(edgeLabel(d))
        }
    }
}