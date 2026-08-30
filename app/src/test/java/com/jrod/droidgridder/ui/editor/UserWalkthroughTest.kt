package com.jrod.droidgridder.ui.editor

import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room
import com.jrod.droidgridder.model.boxFoot
import com.jrod.droidgridder.model.displayNames
import com.jrod.droidgridder.model.labelsOccluded
import com.jrod.droidgridder.model.routeExit
import com.jrod.droidgridder.model.unitBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The end-to-end question: build a map the way a USER builds one — driving the
 * real ViewModel's real gestures (wheel go, naming, self-links, merge with
 * re-home), then Tidy then Relax — and check the result is clean under today's
 * rules: creation anchors on bearing rays, no box/label occlusion, compass
 * alignment, routed lines clear of boxes, layout idempotent (settled rooms
 * stay settled). The graph is a Zork-style walk: a house with an attic, a
 * grating corridor, a contradictory 4-room maze (including a self-loop and a
 * phantom merge that closes the loop with the bearings disagreeing), and two
 * dead ends (duplicate names, for the display-numbering check).
 */
class UserWalkthroughTest {
    @get:Rule
    val tmp = TemporaryFolder()


    @Test
    fun `map built like a user is clean under current rules`() {
        val map = MapFile(
            id = "m1", name = "Zork-ish", createdAt = 0L, updatedAt = 0L,
            rooms = listOf(Room(id = "lr", name = "Living Room", x = 0f, y = 0f)),
        )
        val store = MapStore(tmp.root); store.save(map)
        val vm = MapEditorViewModel("m1", store)
        vm.select("lr")

        fun name(i: Int, n: String) {
            val id = vm.uiState.value.map!!.rooms[i].id
            vm.openRoomEdit(id)
            vm.updateRoomText(id, n, "", "")
        }
        fun walk(d: Direction) {
            val before = vm.uiState.value.map!!.rooms
            val parentId = vm.uiState.value.currentRoomId!!
            vm.openWheel(parentId)
            vm.go(d)
            val after = vm.uiState.value.map!!.rooms
            if (after.size == before.size + 1) {
                // (A) Anchor AT CREATION (the property under test): the new room sits
                // on the bearing ray of the room it was created from. Checked now,
                // before any later re-layout (merge auto-Tidy, Tidy, Relax) can move it.
                val child = after.last(); val parent = before.first { it.id == parentId }
                val anchor = com.jrod.droidgridder.model.anchorPos(parent, d)
                val u = unitBearing(d)
                val dx = child.x - anchor.x; val dy = child.y - anchor.y
                val cross = dx * u.y - dy * u.x            // 0 when on the ray
                val dot = dx * u.x + dy * u.y              // > 0 when ahead of the anchor
                assertTrue(
                    "room '${child.id}' off its $d ray from '${parent.name}' (cross=$cross, dot=$dot)",
                    kotlin.math.abs(cross) < 1f && dot > 0f,
                )
            }
        }

        // ---- the walk (indices: 0 lr) ----
        walk(Direction.E)                    // 1 Kitchen
        name(1, "Kitchen")
        walk(Direction.W)                    // back to Living Room
        walk(Direction.UP)                   // 2 Attic
        name(2, "Attic")
        walk(Direction.DOWN)                 // Living Room
        walk(Direction.E); walk(Direction.E) // 3 Forest
        name(3, "Forest")
        walk(Direction.S)                    // 4 Grating
        name(4, "Grating")
        walk(Direction.E)                    // 5 East-West Passage
        name(5, "East-West Passage")
        walk(Direction.E)                    // 6 Grating Room
        name(6, "Grating Room")
        walk(Direction.W)                    // East-West Passage
        walk(Direction.DOWN)                 // 7 Maze (M1)
        name(7, "Maze")
        // self-loop: the classic "N leads back to the same room"
        val m1 = vm.uiState.value.map!!.rooms[7].id
        vm.openWheel(m1); vm.startLink(Direction.N); vm.completeLink(m1)
        walk(Direction.E)                    // 8 Maze (M2)
        name(8, "Maze")
        val m2 = vm.uiState.value.map!!.rooms[8].id
        vm.openWheel(m2); vm.startLink(Direction.S); vm.completeLink(m2)
        walk(Direction.N)                    // 9 Maze (M3)
        name(9, "Maze")
        walk(Direction.W)                    // 10 Maze (M4)
        name(10, "Maze")
        walk(Direction.S)                    // 11 Maze (M5) — actually M1, the user doesn't know yet
        name(11, "Maze")
        // phantom merge: M5 into M1, its N exit re-homed to M1's free S slot —
        // closing the loop with the bearings DISAGREEING (M4-S->M1 and M1-S->M4).
        val phantomId = vm.uiState.value.map!!.rooms[11].id
        vm.openRoomEdit(phantomId)
        vm.mergeRoom(m1, mapOf(Direction.N to Direction.S))
        val m3 = vm.uiState.value.map!!.rooms[9].id
        val m4 = vm.uiState.value.map!!.rooms[10].id
        vm.openWheel(m3); walk(Direction.E)  // 11 Dead End
        name(11, "Dead End")
        vm.openWheel(m4); walk(Direction.N)  // 12 Dead End
        name(12, "Dead End")

        val pre = vm.uiState.value.map!!

        // ---- the user taps Tidy, then Relax (today's layout rules) ----
        vm.autoTidy()
        vm.relax()
        val out = vm.uiState.value.map!!

        println("[walkthrough] rooms=${out.rooms.size} exits=${out.exits.size}")

        // ---- (B) clean display: no box overlaps, no box/label occlusion ----
        for (i in out.rooms.indices) {
            for (j in i + 1 until out.rooms.size) {
                val a = boxFoot(out.rooms[i].x, out.rooms[i].y)
                val b = boxFoot(out.rooms[j].x, out.rooms[j].y)
                val (px, py) = a.pen(b)
                if (px >= 0.5f && py >= 0.5f) {
                    println("[overlap] '${out.rooms[i].name}' (${out.rooms[i].x},${out.rooms[i].y}) vs " +
                            "'${out.rooms[j].name}' (${out.rooms[j].x},${out.rooms[j].y}) pen=($px,$py)")
                }
                assertTrue(
                    "box overlap '${out.rooms[i].name}'/'${out.rooms[j].name}'",
                    !(px >= 0.5f && py >= 0.5f),
                )
            }
        }
        out.rooms.forEach { r -> println("[pos] '${r.name}' (${r.x},${r.y})") }
        for (i in out.rooms.indices) {
            for (j in i + 1 until out.rooms.size) {
                val a = out.rooms[i]; val b = out.rooms[j]
                val stripA = com.jrod.droidgridder.model.stripFoot(a.x, a.y, a.name)
                val stripB = com.jrod.droidgridder.model.stripFoot(b.x, b.y, b.name)
                val boxA = boxFoot(a.x, a.y); val boxB = boxFoot(b.x, b.y)
                if ((stripA != null && stripA.hits(boxB, com.jrod.droidgridder.model.DISPLAY_MARGIN)) ||
                    (stripB != null && boxA.hits(stripB, com.jrod.droidgridder.model.DISPLAY_MARGIN))) {
                    println("[occlude] box '${a.name}' over strip '${b.name}'" +
                        (if (stripA?.hits(boxB, com.jrod.droidgridder.model.DISPLAY_MARGIN) == true) " [a.strip/b.box]" else "") +
                        (if (stripB?.hits(boxA, com.jrod.droidgridder.model.DISPLAY_MARGIN) == true) " [b.strip/a.box]" else ""))
                }
            }
        }
        assertFalse("label occlusion remains", labelsOccluded(out.rooms))

        // ---- (C) compass fidelity: lines on their bearings. The maze is
        // DELIBERATELY non-Euclidean (the merge re-homed so M1 -S-> M4 and
        // M4 -S-> M1 both point at each other): one of that pair must flip,
        // and the solver's correct resolution is to fully satisfy one edge
        // and fully sacrifice the other (a diagonal compromise would make
        // both partially wrong). Everything else must stay on its side; the
        // sacrificed edge is drawn as a flowchart elbow by the ROUTER, which
        // is exactly ZUG's treatment of a non-Euclidean passage.
        val (align, _) = bearingAlignment(out)
        println("[walkthrough] bearing alignment mean=$align")
        assertTrue("mean bearing alignment $align", align >= 0.90f)
        val pos = out.rooms.associateBy { it.id }
        val flipped = out.exits.filter { e ->
            if (e.from == e.to) return@filter false
            val rest = com.jrod.droidgridder.model.directionOffset(e.direction)
            val rl = kotlin.math.hypot(rest.x.toDouble(), rest.y.toDouble()).toFloat()
            if (rl < 1e-6f) return@filter false
            val a = pos.getValue(e.from); val b = pos.getValue(e.to)
            val dx = b.x - a.x; val dy = b.y - a.y
            val al = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (al < 1e-6f) return@filter false
            (dx * rest.x + dy * rest.y) / (al * rl).toFloat() < 0f
        }
        println("[walkthrough] flipped edges: " + flipped.joinToString { "${pos.getValue(it.from).name}-${it.direction}->${pos.getValue(it.to).name}" })
        assertEquals(
            "only the deliberate contradiction may flip",
            listOf(Triple(m4, m1, Direction.S)),
            flipped.map { Triple(it.from, it.to, it.direction) },
        )

        // ---- (D) routed lines: every passage routes; no routed segment cuts a box ----
        var stubs = 0
        for (e in out.exits) {
            if (e.from == e.to) continue
            val route = routeExit(e, out) ?: continue
            val pts = when (route) {
                is com.jrod.droidgridder.model.ExitRoute.Straight -> listOf(route.from, route.to)
                is com.jrod.droidgridder.model.ExitRoute.Bends -> route.points
                is com.jrod.droidgridder.model.ExitRoute.Stub -> listOf(route.from, route.tip)
                is com.jrod.droidgridder.model.ExitRoute.Loop -> emptyList()
            }
            for (i in 0 until pts.size - 1) {
                for (r in out.rooms) {
                    if (r.id == e.from || r.id == e.to) continue
                    assertFalse(
                        "routed line of exit ${e.direction} ${e.from}->${e.to} cuts room '${r.name}'",
                        com.jrod.droidgridder.model.boxFoot(r.x, r.y)
                            .crosses(pts[i], pts[i + 1], 0f),
                    )
                }
            }
            if (route is com.jrod.droidgridder.model.ExitRoute.Stub) stubs++
        }
        println("[walkthrough] stubs=$stubs")
        assertTrue("too many walled-in stubs: $stubs", stubs <= 2)

        // ---- (E) settled = immobile: a second Relax changes nothing ----
        val before = out
        vm.relax()
        assertEquals("relax is not idempotent — settled rooms drifted", before, vm.uiState.value.map)

        // ---- (F) merge + display naming ----
        assertFalse("phantom survived the merge", out.rooms.any { it.id == phantomId })
        val m1In = out.rooms.first { it.id == m1 }
        assertTrue("re-home: M1 should own S->M4",
            out.exits.any { it.from == m1 && it.direction == Direction.S && it.to == m4 })
        assertTrue("repoint: M4's S exit should aim at M1",
            out.exits.any { it.from == m4 && it.direction == Direction.S && it.to == m1 })
        val names = displayNames(out.rooms)
        val mazeNames = out.rooms.filter { it.name == "Maze" }.map { names.getValue(it.id) }.sorted()
        assertEquals(listOf("Maze (1)", "Maze (2)", "Maze (3)", "Maze (4)"), mazeNames)
        val deadNames = out.rooms.filter { it.name == "Dead End" }.map { names.getValue(it.id) }.sorted()
        assertEquals(listOf("Dead End (1)", "Dead End (2)"), deadNames)
        assertEquals("Living Room", names[out.rooms.first { it.id == "lr" }.id])
        assertEquals(m1In.name, "Maze") // stored names are never rewritten
    }

    /** Mean/min cosine between each edge's actual offset and its declared bearing. */
    private fun bearingAlignment(map: MapFile): Pair<Float, Float> {
        val pos = map.rooms.associateBy { it.id }
        var sum = 0f; var min = 1f; var count = 0
        for (e in map.exits) {
            if (e.from == e.to) continue
            val rest = com.jrod.droidgridder.model.directionOffset(e.direction)
            val rl = kotlin.math.hypot(rest.x.toDouble(), rest.y.toDouble()).toFloat()
            if (rl < 1e-6f) continue
            val a = pos.getValue(e.from); val b = pos.getValue(e.to)
            val dx = b.x - a.x; val dy = b.y - a.y
            val al = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (al < 1e-6f) continue
            val cos = (dx * rest.x + dy * rest.y) / (al * rl).toFloat()
            sum += cos; if (cos < min) min = cos; count++
        }
        return if (count == 0) 0f to 1f else sum / count to min
    }
}