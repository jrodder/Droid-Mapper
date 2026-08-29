package com.jrod.droidgridder.model

import kotlin.math.sqrt

/**
 * Pure bearing-solving ("spring") layout: re-places every room so that each
 * compass exit's line lies on its declared bearing. Seeded from [autoTidy]
 * (grid-exact where the maze allows it), then cycled:
 *
 *  1. Projection: for every exit, both endpoints are slid onto the bearing
 *     ray, splitting the perpendicular correction evenly and preserving the
 *     along-bearing distance — so the line keeps its length, only its angle
 *     is fixed.
 *  2. Separation: any pair of rooms closer than [MIN_SEPARATION] × stride is
 *     pushed apart along the line between them, so boxes never overlap.
 *
 * The two steps undo each other's damage in turn: separation may angle a line
 * off its bearing, the next projection straightens it; projection may slide
 * a room back into a neighbor, the next separation pushes it out — along the
 * way the edge's along-bearing distance grows, so contradictory mazes resolve
 * by STRETCHING lines (a relational map, not a static grid) rather than by
 * rotating a relationship off its declared side. Deterministic: seeded from
 * [autoTidy], fixed edge order, fixed iteration count, no randomness.
 *
 * IN/OUT exits have no bearing (containment) and are left to the Tidy seed;
 * the separation pass still keeps their boxes from overlapping. The root room
 * (first room, same contract as [autoTidy]) is re-pinned at the origin LAST,
 * after the separation pass — re-rooting is a pure translation, so bearings
 * are preserved.
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
    val minSep = MIN_SEPARATION * stride
    val minSep2 = minSep * minSep
    for (iter in 0 until ITERATIONS) {
        // 1) Angle: project each edge onto its bearing ray. The perpendicular
        // correction splits evenly between the endpoints; the along-bearing
        // distance is preserved (clamped up to the contact floor so a room can
        // never end up on the wrong side of its neighbor).
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
        // 2) Separation: push any overlapping pair apart along the line
        // between them. The next projection straightens the angled edges again,
        // and the edge's along-bearing distance grows in the process — the
        // line stretches instead of the relationship rotating.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                val d2 = dx * dx + dy * dy
                if (d2 >= minSep2) continue
                var d = sqrt(d2)
                if (d < 1e-6f) {
                    dx = 1f; dy = 0f; d = 1f // exact tie: deterministic nudge
                }
                val push = (minSep - d) / (2f * d)
                x[i] -= dx * push; y[i] -= dy * push
                x[j] += dx * push; y[j] += dy * push
            }
        }
    }

    // Final separation to a fixed point: the projection sweeps above can slide
    // rooms back into neighbors, so clear overlaps until nothing moves (bounded,
    // early exit). Runs to rest, so the push that shapes the final state is the
    // small residual one — bearing angles stay within tolerance.
    for (pass in 0 until COLLIDE_PASSES) {
        var moved = false
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                val d2 = dx * dx + dy * dy
                if (d2 >= minSep2) continue
                var d = sqrt(d2)
                if (d < 1e-6f) {
                    dx = 1f; dy = 0f; d = 1f // exact tie: deterministic nudge
                }
                val push = (minSep - d) / (2f * d)
                x[i] -= dx * push; y[i] -= dy * push
                x[j] += dx * push; y[j] += dy * push
                moved = true
            }
        }
        if (!moved) break
    }

    // Re-root LAST: separation can nudge the root, and re-rooting is a pure
    // translation (bearings preserved), so the root ends at the origin while
    // every line keeps its settled bearing. Same contract as autoTidy.
    val rootX = x[0]
    val rootY = y[0]
    for (i in 0 until n) {
        x[i] -= rootX
        y[i] -= rootY
    }
    return map.copy(rooms = map.rooms.mapIndexed { i, r -> r.copy(x = x[i], y = y[i]) })
}

// v1.6 bearing solver: no forces — angle is the constraint (solved by
// projection), distance is the free variable it preserves; separation
// violations grow that distance (longer lines) instead of angling edges.
private const val ITERATIONS = 300
private const val MIN_ALONG = 0.75f
private const val MIN_SEPARATION = 0.75f
private const val COLLIDE_PASSES = 50