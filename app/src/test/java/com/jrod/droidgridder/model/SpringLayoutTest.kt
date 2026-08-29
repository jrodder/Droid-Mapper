package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpringLayoutTest {
    /**
     * First 20 rooms of the Zork I sample (west_of_house BFS order, house/forest
     * region) — a real subgraph with genuinely conflicting directions, so Tidy
     * produces long edges and crossings that the relaxation can resolve.
     */
    private val zorkSubset = MapFile("m", "m", 0L, 0L,
        rooms = listOf(
            Room(id = "west_of_house", name = "West of House"),
            Room(id = "attic", name = "Attic"),
            Room(id = "canyon_view", name = "Canyon View"),
            Room(id = "clearing1", name = "Clearing"),
            Room(id = "clearing2", name = "Clearing"),
            Room(id = "east_of_house", name = "Behind House"),
            Room(id = "forest1", name = "Forest"),
            Room(id = "forest2", name = "Forest"),
            Room(id = "forest3", name = "Forest"),
            Room(id = "forest4", name = "Forest"),
            Room(id = "forest_path", name = "Forest Path"),
            Room(id = "gallery", name = "Gallery"),
            Room(id = "grating_room", name = "Grating Room"),
            Room(id = "kitchen", name = "Kitchen"),
            Room(id = "north_of_house", name = "North of House"),
            Room(id = "rocky_ledge", name = "Rocky Ledge"),
            Room(id = "south_of_house", name = "South of House"),
            Room(id = "stone_barrow", name = "Stone Barrow"),
            Room(id = "studio", name = "Studio"),
            Room(id = "up_a_tree", name = "Up a Tree"),
        ),
        exits = listOf(
            Exit(id = "e1", from = "attic", direction = Direction.DOWN, to = "kitchen"),
            Exit(id = "e2", from = "kitchen", direction = Direction.UP, to = "attic"),
            Exit(id = "e3", from = "canyon_view", direction = Direction.W, to = "forest4"),
            Exit(id = "e4", from = "forest4", direction = Direction.E, to = "canyon_view"),
            Exit(id = "e5", from = "canyon_view", direction = Direction.DOWN, to = "rocky_ledge"),
            Exit(id = "e6", from = "rocky_ledge", direction = Direction.UP, to = "canyon_view"),
            Exit(id = "e7", from = "clearing1", direction = Direction.W, to = "forest1"),
            Exit(id = "e8", from = "forest1", direction = Direction.E, to = "clearing1"),
            Exit(id = "e9", from = "clearing1", direction = Direction.E, to = "forest2"),
            Exit(id = "e10", from = "forest2", direction = Direction.W, to = "clearing1"),
            Exit(id = "e11", from = "clearing1", direction = Direction.S, to = "forest_path"),
            Exit(id = "e12", from = "forest_path", direction = Direction.N, to = "clearing1"),
            Exit(id = "e13", from = "clearing1", direction = Direction.DOWN, to = "grating_room"),
            Exit(id = "e14", from = "grating_room", direction = Direction.UP, to = "clearing1"),
            Exit(id = "e15", from = "clearing2", direction = Direction.E, to = "canyon_view"),
            Exit(id = "e16", from = "canyon_view", direction = Direction.W, to = "clearing2"),
            Exit(id = "e17", from = "clearing2", direction = Direction.W, to = "east_of_house"),
            Exit(id = "e18", from = "east_of_house", direction = Direction.E, to = "clearing2"),
            Exit(id = "e19", from = "clearing2", direction = Direction.N, to = "forest2"),
            Exit(id = "e20", from = "forest2", direction = Direction.S, to = "clearing2"),
            Exit(id = "e21", from = "clearing2", direction = Direction.S, to = "forest4"),
            Exit(id = "e22", from = "forest4", direction = Direction.N, to = "clearing2"),
            Exit(id = "e23", from = "east_of_house", direction = Direction.W, to = "kitchen"),
            Exit(id = "e24", from = "kitchen", direction = Direction.E, to = "east_of_house"),
            Exit(id = "e25", from = "east_of_house", direction = Direction.NW, to = "north_of_house"),
            Exit(id = "e26", from = "north_of_house", direction = Direction.SE, to = "east_of_house"),
            Exit(id = "e27", from = "east_of_house", direction = Direction.SW, to = "south_of_house"),
            Exit(id = "e28", from = "south_of_house", direction = Direction.NE, to = "east_of_house"),
            Exit(id = "e29", from = "forest3", direction = Direction.S, to = "forest2"),
            Exit(id = "e30", from = "forest2", direction = Direction.N, to = "forest3"),
            Exit(id = "e31", from = "forest4", direction = Direction.W, to = "forest1"),
            Exit(id = "e32", from = "forest1", direction = Direction.E, to = "forest4"),
            Exit(id = "e33", from = "forest4", direction = Direction.NW, to = "south_of_house"),
            Exit(id = "e34", from = "south_of_house", direction = Direction.SE, to = "forest4"),
            Exit(id = "e35", from = "forest_path", direction = Direction.W, to = "forest1"),
            Exit(id = "e36", from = "forest1", direction = Direction.E, to = "forest_path"),
            Exit(id = "e37", from = "forest_path", direction = Direction.E, to = "forest2"),
            Exit(id = "e38", from = "forest2", direction = Direction.W, to = "forest_path"),
            Exit(id = "e39", from = "forest_path", direction = Direction.S, to = "north_of_house"),
            Exit(id = "e40", from = "north_of_house", direction = Direction.N, to = "forest_path"),
            Exit(id = "e41", from = "gallery", direction = Direction.N, to = "studio"),
            Exit(id = "e42", from = "studio", direction = Direction.S, to = "gallery"),
            Exit(id = "e43", from = "kitchen", direction = Direction.DOWN, to = "studio"),
            Exit(id = "e44", from = "studio", direction = Direction.UP, to = "kitchen"),
            Exit(id = "e45", from = "north_of_house", direction = Direction.SW, to = "west_of_house"),
            Exit(id = "e46", from = "west_of_house", direction = Direction.NE, to = "north_of_house"),
            Exit(id = "e47", from = "south_of_house", direction = Direction.NW, to = "west_of_house"),
            Exit(id = "e48", from = "west_of_house", direction = Direction.SE, to = "south_of_house"),
            Exit(id = "e49", from = "up_a_tree", direction = Direction.DOWN, to = "forest_path"),
            Exit(id = "e50", from = "forest_path", direction = Direction.UP, to = "up_a_tree"),
            Exit(id = "e51", from = "west_of_house", direction = Direction.SW, to = "stone_barrow"),
            Exit(id = "e52", from = "stone_barrow", direction = Direction.NE, to = "west_of_house"),
        ),
    )

    /**
     * v1.6 pin rule, the user's testing-map scenario: a = TESTING, b = tunnel,
     * c = attic, d = eastern tunnel. a-SW-b and b-E-d and a-S-c all want to put
     * c and d on the same cell (b-E from SW(a) lands on S(a)), so no placement
     * satisfies the exact strides. The solver must keep every line ON its
     * declared bearing — stretching distances (longer lines) — instead of
     * angling them, and keep the two rooms' boxes apart.
     */
    @Test
    fun `spring layout keeps a contradictory loop on its declared bearings`() {
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(Room(id = "a"), Room(id = "b"), Room(id = "c"), Room(id = "d")),
            exits = listOf(
                Exit("1", "a", Direction.SW, "b"), Exit("2", "b", Direction.NE, "a"),
                Exit("3", "a", Direction.S, "c"),  Exit("4", "c", Direction.N, "a"),
                Exit("5", "b", Direction.E, "d"),  Exit("6", "d", Direction.W, "b")))
        val out = springLayout(m)
        val p = out.rooms.associateBy { it.id }
        fun offBearing(e: Exit): Float {
            val rest = directionOffset(e.direction)
            val rl = kotlin.math.hypot(rest.x.toDouble(), rest.y.toDouble()).toFloat()
            val a = p.getValue(e.from)
            val b = p.getValue(e.to)
            val dx = b.x - a.x
            val dy = b.y - a.y
            val al = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            // 2D cross with the unit bearing: |v|·sin(angle); 0 = exactly on bearing.
            return abs((dx * rest.y / rl - dy * rest.x / rl) / al)
        }
        for (e in m.exits) {
            assertTrue("${e.direction.name} edge off its bearing by ${offBearing(e)}",
                offBearing(e) < 0.05f)
        }
        // The stretch is real: attic and eastern tunnel are separated, not stacked.
        val c = p["c"]!!
        val d = p["d"]!!
        val sep = kotlin.math.hypot((d.x - c.x).toDouble(), (d.y - c.y).toDouble()).toFloat()
        assertTrue("attic/eastern tunnel overlap: $sep", sep >= 0.74f * GRID_STEP)
    }

    /**
     * Mean/min cosine between each bearing edge's actual offset and its declared
     * bearing (1.0 = on bearing at any distance, < 0 = flipped to the wrong
     * side). IN/OUT have no bearing and are skipped.
     */
    private fun bearingAlignment(map: MapFile): Pair<Float, Float> {
        val pos = map.rooms.associateBy { it.id }
        var sum = 0f
        var min = 1f
        var count = 0
        for (e in map.exits) {
            if (e.from == e.to) continue
            val rest = directionOffset(e.direction)
            val rl = kotlin.math.hypot(rest.x.toDouble(), rest.y.toDouble()).toFloat()
            if (rl < 1e-6f) continue
            val a = pos.getValue(e.from)
            val b = pos.getValue(e.to)
            val dx = b.x - a.x
            val dy = b.y - a.y
            val al = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (al < 1e-6f) continue
            val cos = (dx * rest.x + dy * rest.y) / (al * rl).toFloat()
            sum += cos
            if (cos < min) min = cos
            count++
        }
        return if (count == 0) 0f to 1f else sum / count to min
    }

    @Test
    fun `spring layout keeps conflicting neighbors on their declared compass side`() {
        val tidy = autoTidy(zorkSubset)
        val spring = springLayout(zorkSubset)
        val (springAlign, springMin) = bearingAlignment(spring)
        println("[v1.6 bearing] tidy align ${bearingAlignment(tidy).first} | spring align $springAlign min $springMin")
        // The pin rule on a genuinely conflicting maze: nearly every line sits on
        // its declared bearing (beats the grid, which is 0.91 on this subset),
        // and no room ends up on the wrong side of a neighbor.
        assertTrue("spring compass alignment $springAlign should be high", springAlign > 0.93f)
        assertTrue("a neighbor is on the wrong side: min align $springMin", springMin > 0f)
    }

    @Test
    fun `spring layout keeps the root at the origin and rooms at least a box apart`() {
        val out = springLayout(zorkSubset)
        val root = out.rooms.first()
        assertEquals(0f, root.x, 0.01f)
        assertEquals(0f, root.y, 0.01f)
        var min = Float.MAX_VALUE
        for (i in out.rooms.indices) {
            for (j in i + 1 until out.rooms.size) {
                val d = kotlin.math.hypot(
                    (out.rooms[j].x - out.rooms[i].x).toDouble(),
                    (out.rooms[j].y - out.rooms[i].y).toDouble(),
                ).toFloat()
                min = minOf(min, d)
            }
        }
        assertTrue("rooms overlap: min distance $min < ${0.74f * GRID_STEP}", min >= 0.74f * GRID_STEP)
    }

    @Test
    fun `spring layout is deterministic`() {
        assertEquals(springLayout(zorkSubset), springLayout(zorkSubset))
    }

    @Test
    fun `spring layout pins a single room at the origin like tidy`() {
        val m = MapFile("m", "m", 0L, 0L, rooms = listOf(Room(id = "a", x = 3f, y = 4f)), exits = emptyList())
        val out = springLayout(m)
        assertEquals(0f, out.rooms.single().x, 0.01f) // same root contract as autoTidy
        assertEquals(0f, out.rooms.single().y, 0.01f)
    }

    @Test fun `IN pair settles at contact distance`() {
        // Containment: rest vector zero pulls the pair together; the collision
        // pass pins them at the 0.75*stride contact floor (boxes touch, no overlap).
        val m = MapFile("m", "m", 0L, 0L,
            rooms = listOf(Room(id = "a"), Room(id = "b")),
            exits = listOf(Exit("1", "a", Direction.IN, "b"), Exit("2", "b", Direction.OUT, "a")))
        val out = springLayout(m)
        val byId = out.rooms.associateBy { it.id }
        val dist = kotlin.math.hypot((byId["b"]!!.x - byId["a"]!!.x).toDouble(),
                                     (byId["b"]!!.y - byId["a"]!!.y).toDouble())
        val s = GRID_STEP.toDouble()
        assertTrue("contact distance expected, got ${dist / s} strides", dist >= 0.7 * s && dist <= 1.2 * s)
    }
}