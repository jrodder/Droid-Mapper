package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class EdgeRoutingTest {
    private fun room(id: String, x: Float, y: Float, name: String = "") =
        Room(id = id, name = name, x = x, y = y)
    private fun exit(id: String, from: String, d: Direction, to: String) =
        Exit(id = id, from = from, direction = d, to = to)

    @Test fun `anchorPos pins cardinals at midpoints and diagonals at corners`() {
        val r = room("a", 100f, 200f)
        val h = ROOM_BOX_SIZE / 2f
        assertEquals(Pos(100f, 200f - h), anchorPos(r, Direction.N))
        assertEquals(Pos(100f, 200f + h), anchorPos(r, Direction.S))
        assertEquals(Pos(100f + h, 200f), anchorPos(r, Direction.E))
        assertEquals(Pos(100f - h, 200f), anchorPos(r, Direction.W))
        assertEquals(Pos(100f + h, 200f - h), anchorPos(r, Direction.NE))
        assertEquals(Pos(100f - h, 200f + h), anchorPos(r, Direction.SW))
        assertEquals(Pos(100f, 200f), anchorPos(r, Direction.IN))
    }

    @Test fun `hasMirror is true only for the reverse-direction reverse record`() {
        val a = exit("1", "A", Direction.E, "B")
        val mirror = exit("2", "B", Direction.W, "A")
        val other = exit("3", "B", Direction.W, "C")
        assertTrue(hasMirror(a, listOf(a, mirror)))
        assertFalse(hasMirror(a, listOf(a, other)))
        assertFalse(hasMirror(a, listOf(a)))
    }

    @Test fun `clear corridor routes straight`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(room("A", 0f, 0f), room("B", 2 * GRID_STEP, 0f)),
            exits = listOf(exit("1", "A", Direction.E, "B")))
        val r = routeExit(m.exits[0], m)
        assertEquals(ExitRoute.Straight(anchorPos(m.rooms[0], Direction.E),
                                         anchorPos(m.rooms[1], Direction.W)), r)
    }

    @Test fun `blocker on the line forces an orthogonal detour`() {
        // Blocker sits on the A-E-B straight line.
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(room("A", 0f, 0f), room("B", 2 * GRID_STEP, 0f),
                           room("X", GRID_STEP, 0f, "blocker")),
            exits = listOf(exit("1", "A", Direction.E, "B")))
        val r = (routeExit(m.exits[0], m) as? ExitRoute.Bends)
            ?: error("expected Bends, got ${routeExit(m.exits[0], m)}")
        val pts = r.points
        assertEquals(anchorPos(m.rooms[0], Direction.E), pts.first())
        assertEquals(anchorPos(m.rooms[1], Direction.W), pts.last())
        // axis-aligned segments
        for (i in 0 until pts.size - 1) {
            val (p, q) = pts[i] to pts[i + 1]
            assertTrue("segment not axis-aligned: $p -> $q",
                abs(p.x - q.x) < 0.01f || abs(p.y - q.y) < 0.01f)
        }
        // no segment crosses the blocker box
        val bx = boxFoot(GRID_STEP, 0f)
        for (i in 0 until pts.size - 1) {
            assertFalse("segment crosses blocker", bx.crosses(pts[i], pts[i + 1], ROUTE_MARGIN))
        }
    }

    @Test fun `routing is deterministic`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(room("A", 0f, 0f), room("B", GRID_STEP, GRID_STEP),
                           room("C", GRID_STEP, 0f, "n"), room("D", 0f, GRID_STEP, "w")),
            exits = listOf(exit("1", "A", Direction.S, "D"),
                           exit("2", "D", Direction.N, "A"),
                           exit("3", "A", Direction.E, "C"),
                           exit("4", "C", Direction.W, "A")))
        assertEquals(m.exits.map { routeExit(it, m) }, m.exits.map { routeExit(it, m) })
    }

    @Test fun `fully walled edge falls back to a labeled stub`() {
        // Every candidate route is walled: the blocker column (x=180) is
        // filled on the row (kills the straight + all column candidates,
        // whose y=0 segment must cross x=180) and at both gutter rows
        // (kills the row candidates' horizontal segments at y=+/-90).
        val rooms = listOf(
            room("A", 0f, 0f), room("B", 2 * GRID_STEP, 0f, "B"),
            room("w0", GRID_STEP, 0f),
            room("w1", GRID_STEP, -90f), room("w2", GRID_STEP, 90f),
            room("w3", GRID_STEP, -2 * GRID_STEP), room("w4", GRID_STEP, 2 * GRID_STEP),
            room("w5", GRID_STEP, -GRID_STEP), room("w6", GRID_STEP, GRID_STEP),
            room("w7", 0f, -GRID_STEP), room("w8", 0f, GRID_STEP),
            room("w9", 0f, -2 * GRID_STEP), room("w10", 0f, 2 * GRID_STEP),
        )
        val m = MapFile("m", "m", 0L, 0L, rooms = rooms,
            exits = listOf(exit("1", "A", Direction.E, "B")))
        val r = routeExit(m.exits[0], m) as? ExitRoute.Stub
            ?: error("expected Stub, got ${routeExit(m.exits[0], m)}")
        assertEquals("B", r.targetName)
        assertEquals(r.from.x + STUB_LEN, r.tip.x, 0.01f) // E bearing = (1, 0)
        assertEquals(r.from.y, r.tip.y, 0.01f)
    }

    @Test fun `containment exits are never routed`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(room("A", 0f, 0f), room("B", GRID_STEP, -GRID_STEP)),
            exits = listOf(exit("1", "A", Direction.IN, "B")))
        assertTrue(routeExit(m.exits[0], m) is ExitRoute.Straight)
    }

    @Test fun `self-exit routes to a loop glyph at the bearing anchor`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(room("A", 0f, 0f), room("B", 2 * GRID_STEP, 0f)),
            exits = listOf(exit("1", "A", Direction.E, "A")))
        val h = ROOM_BOX_SIZE / 2f
        val r = routeExit(m.exits[0], m) as? ExitRoute.Loop
            ?: error("expected Loop, got ${routeExit(m.exits[0], m)}")
        assertEquals(Pos(h, 0f), r.anchor) // E anchor = right edge midpoint
        assertEquals(Direction.E, r.direction)
    }

    @Test fun `containment self-exit is not routed`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(room("A", 0f, 0f)),
            exits = listOf(exit("1", "A", Direction.IN, "A")))
        assertNull(routeExit(m.exits[0], m))
    }

    @Test fun `unitBearing is normalized and defined for every direction`() {
        assertEquals(Pos(1f, 0f), unitBearing(Direction.E))
        assertEquals(Pos(0f, -1f), unitBearing(Direction.N))
        val ne = unitBearing(Direction.NE)
        assertEquals(1f, kotlin.math.hypot(ne.x, ne.y), 0.001f)
        assertEquals(ne.x, -ne.y, 0.001f) // NE = (+, -)
        // containment has no bearing — defined, not zero (guard for degenerate callers)
        val inU = unitBearing(Direction.IN)
        assertEquals(1f, kotlin.math.hypot(inU.x, inU.y), 0.001f)
    }

    // Tidy corridor case (Enchanter's Mountain Trail -> Trail Head -> Top of
    // Lonely Mountain): the source anchor sits inside the middle room's label
    // strip (the tolerated box-over-label graze). The route must step off the
    // anchor under its own box (hidden), swing out past the label's edge, and
    // arrive — not stub, and no segment may run through the label itself.
    @Test fun `anchor inside neighbor label strip routes around the label`() {
        val mt = room("mt", 0f, 0f, "Mountain Trail")
        val th = room("th", 0f, -180f, "Trail Head")          // between them
        val tol = room("tolm", 0f, -360f, "Top of Lonely Mountain")
        val e = exit("e1", "mt", Direction.N, "tolm")
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(mt, th, tol), exits = listOf(e))

        // The graze is real: MT's N anchor is inside Trail Head's strip.
        val strip = stripFoot(th.x, th.y, th.name)!!
        val anchor = anchorPos(mt, Direction.N)
        assertTrue("premise: anchor inside strip", strip.contains(anchor))

        val route = routeExit(e, m) ?: error("expected a route, got Stub")
        val bends = route as? ExitRoute.Bends ?: error("expected Bends, got $route")
        val pts = bends.points
        assertEquals(anchor, pts.first())
        assertEquals(anchorPos(tol, Direction.S), pts.last())
        val box = boxFoot(th.x, th.y)
        for (i in 0 until pts.size - 1) {
            val p = pts[i]; val q = pts[i + 1]
            assertTrue("segment must be axis-aligned", p.x == q.x || p.y == q.y)
            assertFalse("segment $i crosses the box", box.crosses(p, q, ROUTE_MARGIN))
            val mid = Pos((p.x + q.x) / 2f, (p.y + q.y) / 2f)
            assertFalse("segment $i runs through the label", strip.contains(mid))
        }
    }
}