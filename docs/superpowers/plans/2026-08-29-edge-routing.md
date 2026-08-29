# Edge Routing (v1.6.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connectors route around room footprints (Manhattan gutters), one-way passages get arrows, and unroutable edges become labeled stubs — in both Tidy and Relax.

**Architecture:** A pure, deterministic router in the model layer (`routeExit`) returns a sealed `ExitRoute` (straight / bends / stub); the Compose canvas draws whatever the router returns, per undirected room pair, so both layout modes benefit. Routing never mutates the map.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, JUnit4 (JVM unit tests via `./gradlew :app:testDebugUnitTest`).

**Spec:** `docs/superpowers/specs/2026-08-29-edge-routing-design.md`

## Global Constraints

- Deterministic: fixed candidate order, no randomness, no HashMap iteration over unordered keys in routing decisions.
- TDD: failing test → minimal implementation → green → commit. No test-less logic.
- `./gradlew :app:testDebugUnitTest` must end green before a task's commit.
- No push, no tag, no release build, no version bump (user-gated).
- Do not touch `MapGraph.kt` semantics (`go`, `linkToExisting`, `mergeRoom`, …).
- Ponytail style: no new dependencies, no unrequested abstractions; mark deliberate simplifications with `ponytail:` comments.

---

### Task 1: Shared display geometry + model-layer router

**Files:**
- Create: `app/src/main/java/com/jrod/droidgridder/model/EdgeRouting.kt`
- Create: `app/src/test/java/com/jrod/droidgridder/model/EdgeRoutingTest.kt`
- Modify: `app/src/main/java/com/jrod/droidgridder/model/SpringLayout.kt` (delete the moved geometry block, import shared versions)

**Interfaces:**
- Consumes: `MapFile`, `Room`, `Exit`, `Direction`, `Pos`, `GRID_STEP`, `ROOM_BOX_SIZE`, `Direction.opposite()` (all existing, same package).
- Produces (exact signatures Task 2 relies on):

```kotlin
package com.jrod.droidgridder.model

// --- display geometry (moved from SpringLayout.kt, made public) ---
data class Foot(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun pen(o: Foot): Pair<Float, Float>          // (penX, penY), positive = overlap
    fun hits(o: Foot, margin: Float): Boolean
    fun crosses(a: Pos, b: Pos, margin: Float): Boolean  // NEW: segment a–b intersects this
}
fun boxFoot(x: Float, y: Float): Foot
fun stripFoot(x: Float, y: Float, name: String): Foot?  // null for blank name
// LABEL_FONT_WORLD, LABEL_CHAR_W, DISPLAY_MARGIN stay public, same values.
const val STUB_LEN = 40f          // world units
const val ROUTE_MARGIN = 2f

// --- anchors (relocated from MapCanvas) ---
fun anchorPos(room: Room, direction: Direction): Pos

// --- topology ---
fun hasMirror(exit: Exit, exits: Collection<Exit>): Boolean

// --- router ---
sealed class ExitRoute {
    data class Straight(val from: Pos, val to: Pos) : ExitRoute()
    data class Bends(val points: List<Pos>) : ExitRoute()
    data class Stub(val from: Pos, val tip: Pos, val direction: Direction,
                    val targetName: String) : ExitRoute()
}
fun routeExit(exit: Exit, map: MapFile): ExitRoute?
```

- [ ] **Step 1: Write failing tests** (`EdgeRoutingTest.kt`)

```kotlin
package com.jrod.droidgridder.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
        val list = listOf(a, mirror)
        assertTrue(hasMirror(a, list))
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
        val r = routeExit(m.exits[0], m) as? ExitRoute.Bends
            ?: error("expected Bends, got $r")
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
        // A's east side is walled: the blocker column (x=180) is filled on the
        // row AND at both gutter rows (y=+/-90), and A's own column is walled
        // above/below, so every L/Z candidate is blocked.
        val rooms = listOf(
            room("A", 0f, 0f), room("B", 2 * GRID_STEP, 0f),
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
            ?: error("expected Stub, got $r")
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
}
```

- [ ] **Step 2: Run tests, confirm they fail to compile/run**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jrod.droidgridder.model.EdgeRoutingTest"`
Expected: FAIL (unresolved references `routeExit`, `ExitRoute`, …)

- [ ] **Step 3: Implement `EdgeRouting.kt`**

- Move from SpringLayout.kt: the `v1.6 display geometry` block (constants,
  `R` → renamed public `Foot` with `pen`, `hits`, plus new `crosses(a, b, margin)`
  = slab-test segment a–b against the rect inflated by margin), `boxRect` →
  `boxFoot`, `stripRect` → `stripFoot`. Keep `DISPLAY_MARGIN` value 2f.
- `anchorPos` = existing `MapCanvas.exitAnchor` math returning `Pos`.
- `hasMirror` per the signature above.
- `routeExit` per spec §2: straight test → candidate list
  (L1, L2, then 8 gutter VHV/HVH candidates with `g = GRID_STEP/2f`, signs
  `[-1, +1]`; stable-order sort putting cardinal-mismatched first-segments
  last; collapse consecutive equal points; skip self-exits/unknown ids
  returning null) → first candidate with all segments `crosses == false`
  wins → else `Stub`.
- [ ] **Step 4: Run tests until green**

Run: `./gradlew :app:testDebugUnitTest --tests "com.jrod.droidgridder.model.EdgeRoutingTest"`
Expected: PASS (all 7 tests).

- [ ] **Step 5: Refactor SpringLayout.kt onto shared geometry**

Delete the moved block; import from `EdgeRouting.kt`; `labelsOccluded` keeps
its exact public signature. Run full suite:
`./gradlew :app:testDebugUnitTest` — expected: all PASS (93 existing + 7 new).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jrod/droidgridder/model/EdgeRouting.kt \
        app/src/test/java/com/jrod/droidgridder/model/EdgeRoutingTest.kt \
        app/src/main/java/com/jrod/droidgridder/model/SpringLayout.kt
git commit -m "feat: pure edge router (straight/manhattan/stub) + shared display geometry"
```

---

### Task 2: Canvas draws routed connectors

**Files:**
- Modify: `app/src/main/java/com/jrod/droidgridder/ui/editor/MapCanvas.kt`

**Interfaces:**
- Consumes: `routeExit`, `ExitRoute`, `hasMirror`, `anchorPos` (Task 1, exact
  signatures above), existing `CameraState`, `exitAnchor` (kept as a wrapper
  over `anchorPos` for `CameraStateTest`), `MapFile`.
- Produces: nothing new for later tasks.

- [ ] **Step 1: Per-pair route map**

Inside the `Canvas` draw lambda, before the exit loop:

```kotlin
// One route per undirected room pair (mirrors share a polyline), computed
// from animated positions so connectors follow room glides.
val routedMap = map.copy(rooms = map.rooms.map { r ->
    animatedRooms[r.id]?.let { r.copy(x = it.x, y = it.y) } ?: r
})
val routes = HashMap<String, ExitRoute?>()
for (exit in map.exits) {
    if (exit.from == exit.to) continue
    val key = listOf(exit.from, exit.to).sorted().joinToString("|")
    if (!routes.containsKey(key)) routes[key] = routeExit(exit, routedMap)
}
```

The exit draw loop then looks up
`routes[listOf(exit.from, exit.to).sorted().joinToString("|")] ?: continue`.

- [ ] **Step 2: Replace `drawLine` with polyline draw**

Per exit: look up the pair route; map its points world→screen;
`drawPath`/`drawLine` per segment (or one `Path` with `moveTo`/`lineTo`).
Replace the existing arrowhead block: draw when `!hasMirror(exit, map.exits)`,
oriented along the final segment.

- [ ] **Step 3: Stub rendering**

For `ExitRoute.Stub`: draw the short line `from→tip` and `drawText`
`"→ ${targetName}"` near the tip (centered, offset along the bearing's
dominant axis by half the text size + 4px). Use `exitColor`/`selectedColor`
as with lines.

- [ ] **Step 4: Polyline hit-testing**

`exitAt`: recompute `routeExit` per exit (same pure function, deterministic);
distance = min over route segments (stub: its single segment; missing route →
skip). Keep 16px tolerance.

- [ ] **Step 5: Build + full suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/jrod/droidgridder/ui/editor/MapCanvas.kt
git commit -m "feat: draw routed connectors (bends, one-way arrows, labeled stubs)"
```

---

### Task 3: On-device verification (controller, not a subagent task)

- [ ] Install debug APK on `emulator-5554`, open *Zork I (Complete)*,
  screenshot Tidy and Relax; confirm: no connector crosses a box or label,
  maze routes through gutters, stubs only where unavoidable, one-way arrows
  visible, *testing* map unchanged in spirit (eastern tunnel label clear).
- [ ] Tune `STUB_LEN`/`ROUTE_MARGIN`/candidate order only if visibly wrong.
- [ ] Final commit if tuned; report; hold deploy for user's "done".