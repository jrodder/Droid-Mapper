package com.jrod.droidgridder.model

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v1.6 display geometry, in world units (MapCanvas draws the name at
 * 13sp * density, below the box: top 0.5 font below the box bottom, one
 * font tall). Shared by the layout solver and the edge router so both
 * agree on what a room's footprint is. ponytail: density 3.0 is the phone
 * target — calibrate if a low-density device shows occlusion.
 */
const val LABEL_FONT_WORLD = 39f  // 13sp @ 3.0
const val LABEL_CHAR_W = 0.55f    // average character width, in em
private val BOX_HALF = ROOM_BOX_SIZE / 2f
private val STRIP_TOP = BOX_HALF + LABEL_FONT_WORLD / 2f        // 82.5
private val STRIP_BOTTOM = BOX_HALF + LABEL_FONT_WORLD * 1.5f   // 121.5

/** Clearance kept when testing route/label collisions (canvas stroke extends ~1px past edges). */
const val DISPLAY_MARGIN = 2f
/** Extra clearance inflated onto every obstacle footprint for connector routing. */
const val ROUTE_MARGIN = 2f
/** Labeled-stub length in world units (fallback when no candidate route is clear). */
const val STUB_LEN = 40f
/** Self-exit loop glyph (ZUG legend: passage returning to room of origin): stalk
 * out from the anchor, then a circle of this radius centered beyond it. */
const val LOOP_STALK = 18f
const val LOOP_RADIUS = 12f

/** Normalized bearing vector for [d]; IN/OUT have no compass bearing —
 *  defined as "up" so degenerate callers never divide by zero. */
fun unitBearing(d: Direction): Pos {
    val off = directionOffset(d)
    val len = sqrt(off.x * off.x + off.y * off.y)
    return if (len < 1e-6f) Pos(0f, -1f) else Pos(off.x / len, off.y / len)
}

data class Foot(val l: Float, val t: Float, val r: Float, val b: Float) {
    /** Penetration (x, y); positive = overlapping on that axis. */
    fun pen(o: Foot): Pair<Float, Float> =
        (minOf(r, o.r) - maxOf(l, o.l)) to (minOf(b, o.b) - maxOf(t, o.t))

    fun hits(o: Foot, margin: Float): Boolean {
        val (px, py) = pen(o)
        return px > -margin && py > -margin
    }

    /** True when segment p–q intersects this rect inflated by [margin] (slab test). */
    fun crosses(p: Pos, q: Pos, margin: Float): Boolean {
        val dx = q.x - p.x
        val dy = q.y - p.y
        var t0 = 0f
        var t1 = 1f
        if (abs(dx) < 1e-9f) {
            if (p.x < l - margin || p.x > r + margin) return false
        } else {
            var ta = (l - margin - p.x) / dx
            var tb = (r + margin - p.x) / dx
            if (ta > tb) { val tmp = ta; ta = tb; tb = tmp }
            if (ta > t0) t0 = ta
            if (tb < t1) t1 = tb
        }
        if (t0 > t1) return false
        if (abs(dy) < 1e-9f) {
            if (p.y < t - margin || p.y > b + margin) return false
        } else {
            var ta = (t - margin - p.y) / dy
            var tb = (b + margin - p.y) / dy
            if (ta > tb) { val tmp = ta; ta = tb; tb = tmp }
            if (ta > t0) t0 = ta
            if (tb < t1) t1 = tb
        }
        return t0 <= t1
    }
}

fun boxFoot(x: Float, y: Float): Foot =
    Foot(x - BOX_HALF, y - BOX_HALF, x + BOX_HALF, y + BOX_HALF)

/** The label strip below a room (null for an unnamed room — nothing drawn). */
fun stripFoot(x: Float, y: Float, name: String): Foot? {
    if (name.isEmpty()) return null
    val hw = name.length * LABEL_CHAR_W * LABEL_FONT_WORLD / 2f
    return Foot(x - hw, y + STRIP_TOP, x + hw, y + STRIP_BOTTOM)
}

/**
 * v1.6 pin rule: where an exit line meets [room]'s box, in world coords.
 * Cardinals exit at the edge midpoint, diagonals at the exact corner,
 * UP/DOWN at the top/bottom midpoint, IN/OUT at the center.
 */
fun anchorPos(room: Room, direction: Direction): Pos {
    val h = ROOM_BOX_SIZE / 2f
    return when (direction) {
        Direction.N -> Pos(room.x, room.y - h)
        Direction.S -> Pos(room.x, room.y + h)
        Direction.E -> Pos(room.x + h, room.y)
        Direction.W -> Pos(room.x - h, room.y)
        Direction.NE -> Pos(room.x + h, room.y - h)
        Direction.NW -> Pos(room.x - h, room.y - h)
        Direction.SE -> Pos(room.x + h, room.y + h)
        Direction.SW -> Pos(room.x - h, room.y + h)
        Direction.UP -> Pos(room.x, room.y - h)
        Direction.DOWN -> Pos(room.x, room.y + h)
        Direction.IN, Direction.OUT -> Pos(room.x, room.y)
    }
}

/** True iff the reverse record (to→from along the opposite bearing) exists. */
fun hasMirror(exit: Exit, exits: Collection<Exit>): Boolean =
    exits.any { it.from == exit.to && it.to == exit.from && it.direction == exit.direction.opposite() }

sealed class ExitRoute {
    data class Straight(val from: Pos, val to: Pos) : ExitRoute()
    /** Axis-aligned polyline; first = source anchor, last = destination anchor. */
    data class Bends(val points: List<Pos>) : ExitRoute()
    data class Stub(val from: Pos, val tip: Pos, val direction: Direction,
                    val targetName: String) : ExitRoute()
    /** Self-exit (ZUG: passage returning to room of origin): a stalk from
     *  [anchor] along [direction] with a small loop circle at its end. */
    data class Loop(val anchor: Pos, val direction: Direction) : ExitRoute()
}

/**
 * Pure, deterministic obstruction routing for one exit (spec §2). Self-exits
 * route to a [ExitRoute.Loop] (IN/OUT self-exits return null — no bearing to
 * hang the loop on). Unknown room ids return null. IN/OUT are containment:
 * straight center-to-center, never routed. Otherwise the straight anchor-to-
 * anchor segment wins when clear; failing that, the fixed-order Manhattan
 * candidate list (L/Z corners, then half-stride gutter channels) is tried in
 * order and the first fully clear polyline wins; failing all of that, a
 * labeled stub pointing out along the declared bearing names the destination.
 *
 * ponytail: candidate-list routing, not A* — the upgrade path if visible
 * routing failures remain; no routed-line-vs-routed-line crossing avoidance.
 */
fun routeExit(exit: Exit, map: MapFile): ExitRoute? {
    val rooms = map.rooms.associateBy { it.id }
    val fromRoom = rooms[exit.from] ?: return null
    if (exit.from == exit.to) {
        if (exit.direction == Direction.IN || exit.direction == Direction.OUT) return null
        return ExitRoute.Loop(anchorPos(fromRoom, exit.direction), exit.direction)
    }
    val toRoom = rooms[exit.to] ?: return null
    if (exit.direction == Direction.IN || exit.direction == Direction.OUT) {
        return ExitRoute.Straight(Pos(fromRoom.x, fromRoom.y), Pos(toRoom.x, toRoom.y))
    }
    val a = anchorPos(fromRoom, exit.direction)
    val b = anchorPos(toRoom, exit.direction.opposite())
    val obstacles = ArrayList<Foot>()
    for (r in map.rooms) {
        if (r.id == exit.from || r.id == exit.to) continue
        obstacles.add(boxFoot(r.x, r.y))
        stripFoot(r.x, r.y, r.name)?.let { obstacles.add(it) }
    }
    fun clear(pts: List<Pos>): Boolean {
        for (i in 0 until pts.size - 1) {
            for (o in obstacles) if (o.crosses(pts[i], pts[i + 1], ROUTE_MARGIN)) return false
        }
        return true
    }
    if (clear(listOf(a, b))) return ExitRoute.Straight(a, b)

    // Fixed candidate order (deterministic): L1 horizontal-first, L2
    // vertical-first, then the 8 half-stride gutter channels (g = GRID_STEP/2).
    val g = GRID_STEP / 2f
    val cands = ArrayList<List<Pos>>(10)
    cands.add(listOf(a, Pos(b.x, a.y), b))
    cands.add(listOf(a, Pos(a.x, b.y), b))
    for (s in listOf(-1f, 1f)) {
        for (m in listOf(a.y + s * g, b.y + s * g)) {
            cands.add(listOf(a, Pos(a.x, m), Pos(b.x, m), b))
        }
        for (m in listOf(a.x + s * g, b.x + s * g)) {
            cands.add(listOf(a, Pos(m, a.y), Pos(m, b.y), b))
        }
    }
    // Cardinal bearings: candidates whose first segment runs ALONG the bearing
    // come first (a line leaving an E port should leave east). Stable sort keeps
    // the fixed order otherwise.
    if (exit.direction in setOf(Direction.N, Direction.S, Direction.E, Direction.W)) {
        val horiz = exit.direction == Direction.E || exit.direction == Direction.W
        cands.sortBy { p -> if ((p[1].y == p[0].y) == horiz) 0 else 1 }
    }
    for (cand in cands) {
        val pts = collapse(cand)
        if (pts.size >= 2 && clear(pts)) return ExitRoute.Bends(pts)
    }
    val u = unitBearing(exit.direction)
    return ExitRoute.Stub(a, Pos(a.x + u.x * STUB_LEN, a.y + u.y * STUB_LEN),
                           exit.direction, toRoom.name)
}

/** Collapse consecutive equal/zero-length points in a candidate polyline. */
private fun collapse(pts: List<Pos>): List<Pos> {
    val out = ArrayList<Pos>(pts.size)
    for (p in pts) {
        val last = out.lastOrNull()
        if (last == null || abs(last.x - p.x) > 1e-6f || abs(last.y - p.y) > 1e-6f) out.add(p)
    }
    return out
}