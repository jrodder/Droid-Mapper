package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapGraphTest {
    private fun room(id: String, x: Float = 0f, y: Float = 0f) = Room(id = id, x = x, y = y)
    private fun map(vararg rs: Room) = MapFile(id = "m", name = "m", createdAt = 0L, updatedAt = 0L, rooms = rs.toList())

    @Test fun `opposite is symmetric for all directions`() {
        for (d in Direction.entries) assertEquals(d, d.opposite().opposite())
    }

    @Test fun `go with unknown room id throws IllegalArgumentException naming the id`() {
        try {
            go(Direction.N, "missing", map(room("a")))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("missing"))
        }
    }

    @Test fun `go north creates room and reverse exit`() {
        val out = go(Direction.N, "a", map(room("a")))
        assertEquals(2, out.rooms.size)
        val n = out.exits.single { it.from == "a" && it.direction == Direction.N }
        assertEquals(2, out.exits.size)
        val back = out.exits.single { it.from == n.to && it.direction == Direction.S }
        assertEquals("a", back.to)
    }

    @Test fun `go on existing exit does not create a room`() {
        val e = Exit(id = "e", from = "a", direction = Direction.E, to = "b")
        val m = map(room("a"), room("b")).copy(exits = listOf(e))
        assertEquals(m, go(Direction.E, "a", m))
    }

    @Test fun `placeNewRoom nudges when a spot is occupied`() {
        val occupied = listOf(room("a"), room("b", 0f, -GRID_STEP))
        val pos = placeNewRoom(Direction.N, room("a"), occupied)
        assertEquals(-2 * GRID_STEP, pos.y, 0.001f)
        assertEquals(0f, pos.x, 0.001f)
    }

    @Test fun `stride param flows through freePosition and go`() {
        // v1.5 ruling O2 pin: an explicit stride must drive placement (not GRID_STEP).
        val at = freePosition(Pos(0f, 0f), Direction.E, emptyList(), 260f)
        assertEquals(Pos(260f, 0f), at)
        val out = go(Direction.E, "a", map(room("a")), 260f)
        assertEquals(2, out.rooms.size)
        assertEquals(260f, out.rooms.last().x, 0.001f)
        assertEquals(0f, out.rooms.last().y, 0.001f)
        // and nudge math scales with stride: a room at +260 is "near" under stride 260
        val nudged = freePosition(Pos(0f, 0f), Direction.E, listOf(Pos(260f, 0f)), 260f)
        assertEquals(Pos(520f, 0f), nudged)
    }

    @Test fun `linkToExisting adds exit without reverse`() {
        val m = map(room("a"), room("b"))
        val out = linkToExisting(Direction.W, "a", "b", m)
        assertEquals(1, out.exits.size)
        assertEquals("b", out.exits.single().to)
    }

    @Test fun `deleteRoom cascades incoming and outgoing exits`() {
        val m = MapFile(id = "m", name = "m", createdAt = 0L, updatedAt = 0L,
            rooms = listOf(room("a"), room("b"), room("c")),
            exits = listOf(Exit("1", "a", Direction.N, "b"), Exit("2", "c", Direction.S, "b")))
        val out = deleteRoom("b", m)
        assertEquals(listOf("a", "c"), out.rooms.map { it.id }.sorted())
        assertTrue(out.exits.isEmpty())
    }

    @Test fun `redirectExit repoints and deleteExit removes`() {
        val e = Exit("1", "a", Direction.N, "b")
        val m = map(room("a"), room("b"), room("c")).copy(exits = listOf(e))
        assertEquals("c", redirectExit("1", "c", m).exits.single().to)
        assertTrue(deleteExit("1", m).exits.isEmpty())
    }

    @Test fun `updateRoomText sets all three fields`() {
        val m = map(room("a"))
        val out = updateRoomText("a", "n", "d", "t", m)
        assertEquals(Room("a", "n", "d", "t", 0f, 0f), out.rooms.single())
    }
}
