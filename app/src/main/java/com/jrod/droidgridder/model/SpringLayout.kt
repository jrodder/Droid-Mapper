package com.jrod.droidgridder.model

import kotlin.math.sqrt

/**
 * Pure bearing-solving ("spring") layout: re-places every room so that each
 * compass exit's line lies on its declared bearing. Seeded from [autoTidy]
 * (grid-exact where the maze allows it), then cycled:
 *
 *  1. Projection: for every exit, both endpoints are slid onto the bearing
 *     ray, splitting the perpendicular correction evenly. The along-bearing
 *     distance is preserved and clamped up to a floor of [MIN_ALONG] ×
 *     stride — a room can never end up on the wrong side of its neighbor.
 *     The floor sits at 0.85×stride, just above 145.5: at grid spacing (180)
 *     a box grazes the label strip of the room above it, and BELOW 145.5 it
 *     swallows the label entirely — the floor keeps every label alive. It is
 *     deliberately below one full stride: contradictory loops (rooms pinned
 *     by several edges at once) converge cleanly at the contact floor and
 *     oscillate when the projection insists on grid length for every edge.
 *  2. Separation: any pair closer than its Tidy-seed distance is pushed
 *     back out along the line between them. The Tidy seed is already
 *     overlap-free (grid cells), so this can only ever un-pack, never pack
 *     — the display can never end up worse than the grid, and the
 *     projection's work (straightening bearings) rides on top of it.
 *
 * The two steps undo each other's damage in turn: separation may angle a
 * line off its bearing, the next projection straightens it; projection may
 * slide a room back into a neighbor, the next separation pushes it out —
 * along the way the edge's along-bearing distance grows, so contradictory
 * mazes resolve by STRETCHING lines (a relational map, not a static grid)
 * rather than by rotating a relationship off its declared side.
 *
 * A final phase runs footprint separation (box AND label strip, along the
 * least-penetrating axis) to a fixed point. The loop above keeps every pair
 * at its Tidy-seed distance, so only the grid's inherent graze remains —
 * a box ~4.5 units over the label strip of the room one stride below it —
 * and the pushes are tiny, their bearing damage negligible. No projection
 * runs after it, so the pushes are monotone — nothing re-collides a cleared
 * pair. Guarantee: no box overlaps a box, no box swallows a neighbor's
 * label. Deterministic: seeded from [autoTidy], fixed edge order, fixed
 * pass order, no randomness.
 *
 * IN/OUT exits have no bearing (containment) and are left to the Tidy seed;
 * the separation pass still keeps their boxes clear. The root room (first
 * room, same contract as [autoTidy]) is re-pinned at the origin LAST, after
 * the final phase — re-rooting is a pure translation, so bearings are
 * preserved.
 *
 * ponytail: O(E + n²) per pass × [ITERATIONS] — milliseconds for the expected
 * dozens of rooms, still fine into the hundreds; a spatial grid (or
 * Barnes-Hut) is the upgrade path for very large maps.
 */
fun springLayout(map: MapFile, stride: Float = GRID_STEP): MapFile {
    if (map.rooms.isEmpty()) return map
    val start = autoTidy(map, stride)
    val n = start.rooms.size
    val x = FloatArray(n)
    val y = FloatArray(n)
    val index = HashMap<String, Int>(n)
    start.rooms.forEachIndexed { i, r ->
        x[i] = r.x
        y[i] = r.y
        index[r.id] = i
    }
    val names = start.rooms.mapTo(ArrayList(n)) { it.name }
    // Per-pair separation floor = the pair's Tidy-seed distance. The Tidy
    // seed is already overlap-free (grid cells), so keeping every pair at
    // least as far apart as its seed is exactly "never display-worse than
    // the grid": the projection straightens bearings on top, and nothing
    // packs tighter than the grid ever did.
    val seedFloor = FloatArray(n * n)
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val d = sqrt((x[j] - x[i]) * (x[j] - x[i]) + (y[j] - y[i]) * (y[j] - y[i]))
            seedFloor[i * n + j] = d
        }
    }
    // Compass edges as (from, to, unit bearing). IN/OUT (zero rest vector)
    // have no bearing and are skipped; so are self-exits.
    val bearings = ArrayList<Triple<Int, Int, Pos>>(start.exits.size)
    for (e in start.exits) {
        if (e.from == e.to) continue
        val off = directionOffset(e.direction, stride)
        val len = sqrt(off.x * off.x + off.y * off.y)
        if (len < 1e-6f) continue
        bearings.add(Triple(index.getValue(e.from), index.getValue(e.to),
                            Pos(off.x / len, off.y / len)))
    }
    val minAlong = MIN_ALONG * stride
    for (iter in 0 until ITERATIONS) {
        // 1) Angle: project each edge onto its bearing ray. The perpendicular
        // correction splits evenly between the endpoints; the along-bearing
        // distance is preserved (clamped up to the contact floor so a room
        // can never end up on the wrong side of its neighbor).
        for ((a, b, u) in bearings) {
            val vx = x[b] - x[a]
            val vy = y[b] - y[a]
            val along = vx * u.x + vy * u.y
            val target = if (along < minAlong) minAlong else along
            val cx = (u.x * target - vx) / 2f
            val cy = (u.y * target - vy) / 2f
            x[a] -= cx; y[a] -= cy
            x[b] += cx; y[b] += cy
        }
        // 2) Separation: any pair closer than its Tidy-seed distance is
        // pushed back out along the line between them (the seed was
        // overlap-free, so this can only ever un-pack, never pack). The
        // push may angle a line off its bearing; the next projection
        // straightens it, and the edge's along-bearing distance grows in
        // the process — the line stretches instead of the relationship
        // rotating.
        separateToSeedFloor(x, y, seedFloor)
    }

    // 3) Footprint separation to a fixed point (LAST): one more sweep of the
    // same separation, now with no projection left to re-straighten. The
    // loop above already keeps footprints clear, so only small residuals
    // remain and the bearing damage is negligible. The pushes are monotone —
    // nothing re-collides a cleared pair — so this converges. Display
    // guarantee: no box overlaps a box, no box swallows a neighbor's label.
    for (pass in 0 until FOOTPRINT_PASSES) {
        if (!separateFootprints(x, y, names)) break
    }

    // Re-root LAST: the passes above can nudge the root, and re-rooting is a
    // pure translation (bearings preserved), so the root ends at the origin
    // while every line keeps its settled bearing. Same contract as autoTidy.
    val rootX = x[0]
    val rootY = y[0]
    for (i in 0 until n) {
        x[i] -= rootX
        y[i] -= rootY
    }
    return map.copy(rooms = map.rooms.mapIndexed { i, r -> r.copy(x = x[i], y = y[i]) })
}

// v1.6 display geometry, in world units (MapCanvas draws the name at
// 13sp * density, below the box: top 0.5 font below the box bottom, one
// font tall). ponytail: density 3.0 is the phone target — calibrate if a
// low-density device shows occlusion.
const val LABEL_FONT_WORLD = 39f  // 13sp @ 3.0
const val LABEL_CHAR_W = 0.55f    // average character width, in em
private val BOX_HALF = ROOM_BOX_SIZE / 2f
private val STRIP_TOP = BOX_HALF + LABEL_FONT_WORLD / 2f        // 82.5
private val STRIP_BOTTOM = BOX_HALF + LABEL_FONT_WORLD * 1.5f   // 121.5

private data class R(val l: Float, val t: Float, val r: Float, val b: Float) {
    /** Penetration (x, y); positive = overlapping on that axis. */
    fun pen(o: R): Pair<Float, Float> =
        (minOf(r, o.r) - maxOf(l, o.l)) to (minOf(b, o.b) - maxOf(t, o.t))
    fun hits(o: R, margin: Float): Boolean {
        val (px, py) = pen(o)
        return px > -margin && py > -margin
    }
}

private fun boxRect(x: Float, y: Float): R = R(x - BOX_HALF, y - BOX_HALF, x + BOX_HALF, y + BOX_HALF)

/** The label strip below a room (null for an unnamed room — nothing drawn). */
private fun stripRect(x: Float, y: Float, name: String): R? {
    if (name.isEmpty()) return null
    val hw = name.length * LABEL_CHAR_W * LABEL_FONT_WORLD / 2f
    return R(x - hw, y + STRIP_TOP, x + hw, y + STRIP_BOTTOM)
}

/**
 * True when any room's box occludes another room's label strip (either
 * direction) by more than [margin] — the "clean display" property the
 * solver's final footprint phase guarantees.
 */
fun labelsOccluded(rooms: List<Room>, margin: Float = DISPLAY_MARGIN): Boolean {
    for (i in rooms.indices) {
        for (j in i + 1 until rooms.size) {
            val a = rooms[i]
            val b = rooms[j]
            val boxA = boxRect(a.x, a.y)
            val boxB = boxRect(b.x, b.y)
            val stripA = stripRect(a.x, a.y, a.name)
            val stripB = stripRect(b.x, b.y, b.name)
            if ((stripA != null && stripA.hits(boxB, margin)) ||
                (stripB != null && boxA.hits(stripB, margin))) return true
        }
    }
    return false
}

/**
 * One separation pass: every pair closer than its Tidy-seed floor
 * ([seedFloor], flattened i·n+j) is pushed back out to exactly the floor,
 * along the line between them (Gauss–Seidel in fixed index order:
 * deterministic). Returns whether anything moved (false = clean).
 */
private fun separateToSeedFloor(x: FloatArray, y: FloatArray, seedFloor: FloatArray): Boolean {
    val n = x.size
    var moved = false
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val floor = seedFloor[i * n + j]
            var dx = x[j] - x[i]
            var dy = y[j] - y[i]
            val d2 = dx * dx + dy * dy
            if (d2 >= floor * floor) continue
            var d = sqrt(d2)
            if (d < 1e-6f) {
                dx = 1f; dy = 0f; d = 1f // exact tie: deterministic nudge
            }
            val push = (floor - d) / (2f * d)
            x[i] -= dx * push; y[i] -= dy * push
            x[j] += dx * push; y[j] += dy * push
            moved = true
        }
    }
    return moved
}

/**
 * One footprint separation pass (Gauss–Seidel in fixed index order:
 * deterministic): for every pair whose boxes overlap, or whose label strip
 * overlaps the other's box (either direction), push them apart along the
 * least-penetrating axis by the full penetration plus [DISPLAY_MARGIN],
 * split evenly between the endpoints. Used only as the final phase — the
 * main loop keeps every pair at its Tidy-seed distance, so only small
 * residuals remain, and with no projection left to re-straighten, each push
 * is monotone (nothing re-collides a cleared pair) and the phase converges
 * (bounded by [FOOTPRINT_PASSES]). Returns whether anything moved
 * (false = clean).
 */
private fun separateFootprints(x: FloatArray, y: FloatArray, names: List<String>): Boolean {
    var moved = false
    for (i in x.indices) {
        for (j in i + 1 until x.size) {
            val boxI = boxRect(x[i], y[i])
            val boxJ = boxRect(x[j], y[j])
            val stripI = stripRect(x[i], y[i], names[i])
            val stripJ = stripRect(x[j], y[j], names[j])
            // Tightest overlap across all rect pairs and both axes. A pair
            // only overlaps when BOTH axes penetrate; among real overlaps we
            // take the least-penetrating axis to push along.
            var best = Float.MAX_VALUE
            var axisX = true
            fun consider(p: Pair<Float, Float>) {
                if (p.first <= 0f || p.second <= 0f) return
                if (p.first < best) { best = p.first; axisX = true }
                if (p.second < best) { best = p.second; axisX = false }
            }
            consider(boxI.pen(boxJ))
            if (stripI != null) consider(stripI.pen(boxJ))
            if (stripJ != null) consider(boxI.pen(stripJ))
            if (best == Float.MAX_VALUE) continue
            val d = (best + DISPLAY_MARGIN) / 2f
            if (axisX) {
                val s = if (x[j] >= x[i]) 1f else -1f
                x[i] -= s * d; x[j] += s * d
            } else {
                val s = if (y[j] >= y[i]) 1f else -1f
                y[i] -= s * d; y[j] += s * d
            }
            moved = true
        }
    }
    return moved
}

// v1.6 bearing solver: no forces — angle is the constraint (solved by
// projection), distance is the free variable it preserves; separation
// violations grow that distance (longer lines) instead of angling edges.
private const val ITERATIONS = 300
// The contact floor must stay ≥ 145.5 (≈0.81×stride): below that a box's top
// edge swallows the label strip of the room above it (box top at d-63 <
// strip top at 82.5). Below one full stride so contradictory loops
// (rooms pinned by several edges at once) can converge at the contact floor
// instead of oscillating against a grid-length insistence.
private const val MIN_ALONG = 0.85f   // contact floor, × stride
private const val FOOTPRINT_PASSES = 500 // final footprint fixed-point passes (bounded)

// [DISPLAY_MARGIN] of clearance is kept when testing occlusion (the canvas
// stroke extends ~1px past the box edge).
const val DISPLAY_MARGIN = 2f