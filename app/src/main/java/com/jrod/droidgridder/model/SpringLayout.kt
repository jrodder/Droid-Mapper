package com.jrod.droidgridder.model

import kotlin.math.sqrt

/**
 * Pure force-directed ("spring") layout: relaxes the map's connections so
 * connected rooms settle near their compass-rest slot (one stride along the
 * exit direction) while unrelated rooms repel each other and a centering
 * gravity keeps the map compact. Seeded from the [autoTidy] layout, so the
 * result is deterministic (no randomness, fixed iteration count) and the same
 * input always yields the same output.
 *
 * Post-processing: the whole map is translated so the root room (first room,
 * same contract as [autoTidy]) sits at the origin, and a bounded collision
 * pass pushes any pair of rooms closer than [MIN_SEPARATION] × stride apart,
 * so room boxes never overlap (the relaxation alone can pack conflicting
 * triangle regions tighter than a room box).
 *
 * Rooms unreachable from the root keep their Tidy position and are pulled
 * into the layout by the gravity (whereas [autoTidy] leaves them in place).
 *
 * ponytail: O(n²) per iteration — milliseconds for the expected dozens of
 * rooms, still fine into the hundreds; a spatial grid (or Barnes-Hut) is the
 * upgrade path for very large maps.
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
    val edges = ArrayList<Triple<Int, Int, Pos>>(start.exits.size)
    for (e in start.exits) {
        if (e.from == e.to) continue // ponytail: self-exits have no rest vector (autoTidy skips them too)
        edges.add(Triple(index.getValue(e.from), index.getValue(e.to), directionOffset(e.direction, stride)))
    }

    val fx = FloatArray(n)
    val fy = FloatArray(n)
    val temp0 = SPRING_COOL_START * stride
    val temp1 = SPRING_COOL_END * stride
    for (iter in 0 until SPRING_ITERATIONS) {
        val temp = temp0 + (temp1 - temp0) * (iter.toFloat() / (SPRING_ITERATIONS - 1).toFloat())
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += x[i]
            cy += y[i]
        }
        cx /= n
        cy /= n
        fx.fill(0f)
        fy.fill(0f)
        // Two-sided compass springs: each edge wants its endpoints one stride
        // apart along the declared direction; both endpoints share the pull.
        for ((a, b, off) in edges) {
            val ex = (x[b] - x[a]) - off.x
            val ey = (y[b] - y[a]) - off.y
            fx[a] += ex * SPRING_K; fy[a] += ey * SPRING_K
            fx[b] -= ex * SPRING_K; fy[b] -= ey * SPRING_K
        }
        // Inverse-distance repulsion keeps unrelated rooms from stacking.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                var d2 = dx * dx + dy * dy
                if (d2 < 1e-6f) {
                    dx = 1f; dy = 0f; d2 = 1f // exact tie: deterministic nudge
                }
                val d = sqrt(d2)
                val f = REPEL_K * stride * stride / maxOf(d, REPEL_FLOOR * stride)
                val ux = dx / d
                val uy = dy / d
                fx[i] -= f * ux; fy[i] -= f * uy
                fx[j] += f * ux; fy[j] += f * uy
            }
        }
        // Centering gravity: the map must not balloon into a thin ribbon.
        for (i in 0 until n) {
            fx[i] += (cx - x[i]) * GRAVITY_K
            fy[i] += (cy - y[i]) * GRAVITY_K
        }
        // Cool: moves are capped by the (decaying) temperature.
        for (i in 0 until n) {
            val len = sqrt(fx[i] * fx[i] + fy[i] * fy[i])
            if (len <= 0f) continue
            val m = minOf(len, temp) / len
            x[i] += fx[i] * m
            y[i] += fy[i] * m
        }
    }

    // Re-root: like autoTidy, the root room ends up at the origin.
    val rootX = x[0]
    val rootY = y[0]
    for (i in 0 until n) {
        x[i] -= rootX
        y[i] -= rootY
    }
    // Bounded collision pass: separate any pair closer than MIN_SEPARATION.
    val minSep = MIN_SEPARATION * stride
    val minSep2 = minSep * minSep
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
                    dx = 1f; dy = 0f; d = 1f
                }
                val push = (minSep - d) / (2f * d)
                x[i] -= dx * push; y[i] -= dy * push
                x[j] += dx * push; y[j] += dy * push
                moved = true
            }
        }
        if (!moved) break
    }
    return map.copy(rooms = map.rooms.mapIndexed { i, r -> r.copy(x = x[i], y = y[i]) })
}

// Tuned on the 110-room Zork I sample (see progress ledger, 2026-08-27):
// fewest-crossings / shortest-total-edge constants that stay compact.
private const val SPRING_K = 0.5f
private const val REPEL_K = 0.15f
private const val REPEL_FLOOR = 0.15f
private const val GRAVITY_K = 0.05f
private const val SPRING_ITERATIONS = 700
private const val SPRING_COOL_START = 0.7f
private const val SPRING_COOL_END = 0.01f
private const val COLLIDE_PASSES = 50
private const val MIN_SEPARATION = 0.75f