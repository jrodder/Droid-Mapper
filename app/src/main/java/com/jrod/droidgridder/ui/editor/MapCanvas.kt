package com.jrod.droidgridder.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.ExitRoute
import com.jrod.droidgridder.model.GRID_STEP
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.ROOM_BOX_SIZE
import com.jrod.droidgridder.model.Pos
import com.jrod.droidgridder.model.Room
import com.jrod.droidgridder.model.anchorPos
import com.jrod.droidgridder.model.hasMirror
import com.jrod.droidgridder.model.opposite
import com.jrod.droidgridder.model.routeExit
import com.jrod.droidgridder.model.unitBearing
import com.jrod.droidgridder.model.LABEL_CHAR_W
import com.jrod.droidgridder.model.LABEL_FONT_WORLD
import com.jrod.droidgridder.model.LOOP_RADIUS
import com.jrod.droidgridder.model.LOOP_STALK
import com.jrod.droidgridder.model.displayNames
import com.jrod.droidgridder.model.edgeLabel

/**
 * World<->screen transform for the editor canvas. The fields are snapshot state so
 * gesture updates invalidate only the canvas draw, not the composable tree.
 */
class CameraState {
    var scale: Float by mutableStateOf(1f)
    var offsetX: Float by mutableStateOf(0f)
    var offsetY: Float by mutableStateOf(0f)

    val offset: Offset get() = Offset(offsetX, offsetY)

    fun screenToWorld(screen: Offset): Pos =
        Pos((screen.x - offsetX) / scale, (screen.y - offsetY) / scale)

    fun worldToScreen(world: Pos): Offset =
        Offset(world.x * scale + offsetX, world.y * scale + offsetY)

    fun panBy(pan: Offset) {
        offsetX += pan.x
        offsetY += pan.y
    }

    /** Zoom around [pivot] (screen coords); scale is clamped to [MIN_SCALE]..[MAX_SCALE]. */
    fun zoomBy(factor: Float, pivot: Offset) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val applied = newScale / scale
        offsetX = pivot.x - (pivot.x - offsetX) * applied
        offsetY = pivot.y - (pivot.y - offsetY) * applied
        scale = newScale
    }

    fun centerOn(world: Pos, canvasSize: Size) {
        scale = 1f
        offsetX = canvasSize.width / 2f - world.x
        offsetY = canvasSize.height / 2f - world.y
    }

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 4f
    }
}

// Room box in world units: model's ROOM_BOX_SIZE (~GRID_STEP * 0.7 per the task brief).
private const val ROOM_BOX_RADIUS = 12f

/**
 * v1.6 pin rule (model math lives in EdgeRouting.anchorPos — the router and the
 * canvas must agree on where lines meet boxes). Thin Offset wrapper kept for
 * hit-testing and CameraStateTest.
 */
fun exitAnchor(room: Room, direction: Direction): Offset {
    val p = anchorPos(room, direction)
    return Offset(p.x, p.y)
}

/** The route's world points as a drawable polyline (Stub = from..tip;
 *  Loop = anchor..end of the stalk, which is what hit-testing needs). */
private fun ExitRoute.polyline(): List<Pos> = when (this) {
    is ExitRoute.Straight -> listOf(from, to)
    is ExitRoute.Bends -> points
    is ExitRoute.Stub -> listOf(from, tip)
    is ExitRoute.Loop -> {
        val u = unitBearing(direction)
        val reach = LOOP_STALK + LOOP_RADIUS
        listOf(anchor, Pos(anchor.x + u.x * reach, anchor.y + u.y * reach))
    }
}

/** Animated per-room world position plus first-draw pop scale (Task 7). */
private data class RoomAnim(val x: Float, val y: Float, val pop: Float)

/**
 * Renders the room graph and handles pan/zoom/tap gestures. Tap callbacks receive
 * the hit-tested room id (or an empty-canvas signal); wiring them to editor state
 * is Task 5.
 */
@Composable
fun MapCanvas(
    state: MapEditorUiState,
    camera: CameraState,
    onTapRoom: (String) -> Unit,
    onDoubleTapRoom: (String) -> Unit,
    onLongPressExit: (String) -> Unit,
    onTapEmpty: () -> Unit,
) {
    val map = state.map
    val background = MaterialTheme.colorScheme.background
    val roomFill = MaterialTheme.colorScheme.surface
    val boxStroke = MaterialTheme.colorScheme.outline
    val currentColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.secondary
    val exitColor = MaterialTheme.colorScheme.outline
    val roomNameColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    // Draw-scope size, remembered so the tap handler can center the camera
    // on a tapped stub's target (centerOn needs the canvas rect).
    val canvasSize = remember { mutableStateOf(Size.Zero) }

    // Task 7: per-room animation state keyed by room id (stable slots), so re-layouts
    // (go-new, autoTidy, undo) glide the box to its new position; `scale` starts at 0
    // on a room's first composition -> new rooms pop in. animateFloatAsState has no
    // initial-value parameter in this compose version, so Animatable carries both.
    // ponytail: stale entries for deleted rooms linger; bounded by rooms seen this session.
    val animatedRooms = remember { mutableStateMapOf<String, RoomAnim>() }
    map?.rooms?.forEach { room ->
        key(room.id) {
            val x = remember { Animatable(room.x) }
            val y = remember { Animatable(room.y) }
            val scale = remember { Animatable(0f) }
            LaunchedEffect(room.x, room.y) {
                x.animateTo(room.x)
                y.animateTo(room.y)
            }
            LaunchedEffect(Unit) {
                scale.snapTo(0f)
                scale.animateTo(1f)
            }
            animatedRooms[room.id] = RoomAnim(x.value, y.value, scale.value)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            // Keyed on map so the hit-test picks up rooms created after go/link (stale-capture fix).
            .pointerInput(map) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        roomAt(map, camera, pos)?.let(onDoubleTapRoom)
                    },
                    // v1.1: long-press is a no-op on rooms and empty canvas. detectTapGestures
                    // still consumes the press in its long-press branch, so it never falls
                    // through to a delayed single tap either.
                    onLongPress = { pos -> exitAt(map, camera, pos)?.let(onLongPressExit) },
                    onTap = { pos ->
                        // Tapped stub (walled-in passage): center the camera on
                        // the stub's named target — the ZUG "to Troll Room" hop.
                        val stubTarget = stubTargetAt(map, camera, pos)
                        if (stubTarget != null) {
                            map?.rooms?.firstOrNull { it.id == stubTarget }?.let { r ->
                                if (canvasSize.value != Size.Zero) {
                                    camera.centerOn(Pos(r.x, r.y), canvasSize.value)
                                }
                            }
                        } else {
                            val room = roomAt(map, camera, pos)
                            if (room != null) onTapRoom(room) else onTapEmpty()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    camera.panBy(pan)
                    camera.zoomBy(zoom, centroid)
                }
            },
    ) {
        drawRect(color = background)
        if (map == null) return@Canvas

        canvasSize.value = size
        val roomsById = map.rooms.associateBy { it.id }
        val names = displayNames(map.rooms)

        // v1.5 ruling O1: pure world scaling, no clamp — labels keep constant world width at
        // every zoom (overlap-free by layout stride), pinch-in makes them larger.
        val nameFont = (13f * camera.scale).sp
        val radius = CornerRadius(ROOM_BOX_RADIUS * camera.scale)

        // v1.6.1 routed connectors: each exit is routed around the OTHER rooms'
        // footprints (Manhattan gutters; labeled stub when walled in; a loop
        // glyph for self-exits). Routes are pure functions of the current
        // positions, so they track the animated glides. A mirror pair computes
        // the identical geometry reversed, so mirrors still draw as one line.
        val routedMap = map.copy(rooms = map.rooms.map { r ->
            animatedRooms[r.id]?.let { r.copy(x = it.x, y = it.y) } ?: r
        })

        // Exits first (under the boxes): polylines run from the source box's
        // direction anchor to the destination box's opposite-direction anchor,
        // bent around obstructions.
        // ponytail: no connector draw-in stroke (Task 7 optional) — per-exit animation
        // state for little visual gain.
        for (exit in map.exits) {
            val from = roomsById[exit.from] ?: continue
            val to = roomsById[exit.to] ?: continue
            // v1.6 ruling Q1 (replaces v1.4 N2's always-blue vertical clause): one rule for
            // every edge — touching the selected room is secondary (green) 3dp; the rest stay
            // outline (grey) 2dp. UP/DOWN follow the same selection rule as all other edges.
            val isSelectedEdge = exit.from == state.selectedRoomId || exit.to == state.selectedRoomId
            val lineColor = if (isSelectedEdge) selectedColor else exitColor
            val lineWidth = if (isSelectedEdge) 3f else 2f
            // Self-exit: ZUG's loop glyph — a stalk out along the bearing with a
            // small circle at its end (passage returning to room of origin).
            if (exit.from == exit.to) {
                val route = routeExit(exit, routedMap) as? ExitRoute.Loop ?: continue
                val a = camera.worldToScreen(route.anchor)
                val u = unitBearing(route.direction)
                val stalkEnd = Offset(a.x + u.x * LOOP_STALK * camera.scale, a.y + u.y * LOOP_STALK * camera.scale)
                val center = Offset(stalkEnd.x + u.x * LOOP_RADIUS * camera.scale, stalkEnd.y + u.y * LOOP_RADIUS * camera.scale)
                drawLine(color = lineColor, start = a, end = stalkEnd, strokeWidth = lineWidth)
                drawCircle(color = lineColor, radius = LOOP_RADIUS * camera.scale, center = center, style = Stroke(width = lineWidth))
                continue
            }
            // One passage pair (mirror records) draws ONCE — from the canonical
            // record (smaller from-room id). The mirror's own route (its own
            // drop-off shape, say) is not drawn: that would be two different
            // polylines for one passage. One-way records have no mirror and
            // draw themselves (with the arrowhead). [mirror] is kept for the
            // label fallback below (either record may carry the traversal text).
            val mirror = if (exit.from > exit.to) {
                map.exits.firstOrNull {
                    it.from == exit.to && it.to == exit.from &&
                        it.direction == exit.direction.opposite()
                }
            } else null
            if (exit.from > exit.to && mirror != null) continue
            val route = routeExit(exit, routedMap) ?: continue
            val pts = route.polyline().map { camera.worldToScreen(it) }
            val path = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            drawPath(path, color = lineColor, style = Stroke(width = lineWidth))
            // One-way passages get an arrowhead at the destination anchor, oriented
            // along the route's final segment (a routed line can arrive along an axis
            // different from its declared bearing). Same color as the line itself.
            // Topology, not the oneWay flag, decides: an arrow only when the reverse
            // record (to→from along the opposite bearing) does not exist.
            val oneWay = !hasMirror(exit, map.exits)
            if (oneWay && pts.size >= 2) {
                val tip = pts.last()
                val prev = pts[pts.size - 2]
                val dx = tip.x - prev.x
                val dy = tip.y - prev.y
                val len = kotlin.math.hypot(dx, dy)
                if (len > 1f) {
                    val ux = dx / len
                    val uy = dy / len
                    val arrowLen = 28f // screen px, zoom-independent (14f read as invisible)
                    val arrowW = 10f
                    // Tip sits exactly on the destination anchor (the line ends there).
                    val baseX = tip.x - ux * arrowLen
                    val baseY = tip.y - uy * arrowLen
                    val px = -uy
                    val py = ux
                    val arrowPath = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(baseX + px * arrowW, baseY + py * arrowW)
                        lineTo(baseX - px * arrowW, baseY - py * arrowW)
                        close()
                    }
                    drawPath(arrowPath, color = lineColor)
                }
            }
            // ZUG canon text on the line: U / D / In / Out for vertical and
            // containment edges (which draw center-to-center), and the
            // traversal action ("climb rope", "slide") for compass passages.
            // Sits at the longest segment's midpoint, pushed above the line —
            // the longest segment is the most likely to be in open space.
            val labelText = if (route !is ExitRoute.Stub) {
                edgeLabel(exit.direction)
                    ?: exit.traversalAction.ifBlank { mirror?.traversalAction.orEmpty() }
            } else null
            if (labelText != null && pts.size >= 2) {
                var bestI = 0
                var bestLen = -1f
                for (i in 0 until pts.size - 1) {
                    val segLen = kotlin.math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
                    if (segLen > bestLen) { bestLen = segLen; bestI = i }
                }
                val mid = Offset(
                    (pts[bestI].x + pts[bestI + 1].x) / 2f,
                    (pts[bestI].y + pts[bestI + 1].y) / 2f,
                )
                val layout = textMeasurer.measure(text = labelText, style = TextStyle(fontSize = (11f * camera.scale).sp))
                drawText(
                    layout, color = lineColor,
                    topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - 6f * camera.scale - layout.size.height / 2f),
                )
            }
            // Walled-in edge: labeled stub naming the destination, pushed out along
            // the bearing's dominant axis so the text doesn't sit on the line.
            if (route is ExitRoute.Stub) {
                val layout = textMeasurer.measure(text = "→ ${route.targetName}", style = TextStyle(fontSize = nameFont))
                val u = unitBearing(route.direction)
                val tip = camera.worldToScreen(route.tip)
                val cx = if (kotlin.math.abs(u.x) > kotlin.math.abs(u.y)) {
                    tip.x + (if (u.x > 0f) 1f else -1f) * (4f + layout.size.width / 2f)
                } else {
                    tip.x
                }
                val cy = if (kotlin.math.abs(u.x) > kotlin.math.abs(u.y)) {
                    tip.y
                } else {
                    tip.y + (if (u.y > 0f) 1f else -1f) * (layout.size.height / 2f)
                }
                drawText(layout, color = lineColor, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
            }
        }

        for (room in map.rooms) {
            // Animated position (Task 7): re-layouts glide; `pop` scales new rooms in.
            val anim = animatedRooms[room.id] ?: RoomAnim(room.x, room.y, 1f)
            val c = camera.worldToScreen(Pos(anim.x, anim.y))
            val boxSize = ROOM_BOX_SIZE * camera.scale * anim.pop
            val half = boxSize / 2f
            val rect = Rect(c.x - half, c.y - half, c.x + half, c.y + half)
            // Dark room ("total darkness"): the box is dimmed — its exits are
            // not yet trustworthy. The name stays bright (identity).
            val fill = if (room.isDark) roomFill.copy(alpha = 0.45f) else roomFill
            val stroke = if (room.isDark) boxStroke.copy(alpha = 0.45f) else boxStroke
            drawRoundRect(
                color = fill, topLeft = rect.topLeft, size = rect.size, cornerRadius = radius,
            )
            drawRoundRect(
                color = stroke, topLeft = rect.topLeft, size = rect.size,
                cornerRadius = radius, style = Stroke(width = 2f),
            )
            // v1.4 ruling N1: one highlight per room, drawn ON the box. Selected -> secondary
            // (green) wins; current -> primary (blue) only when not also selected. No inflated ring.
            if (room.id == state.currentRoomId && room.id != state.selectedRoomId) {
                drawRoundRect(
                    color = currentColor, topLeft = rect.topLeft, size = rect.size,
                    cornerRadius = radius, style = Stroke(width = 3f),
                )
            }
            if (room.id == state.selectedRoomId) {
                drawRoundRect(
                    color = selectedColor, topLeft = rect.topLeft, size = rect.size,
                    cornerRadius = radius, style = Stroke(width = 3f),
                )
            }
            // Room name is drawn under the box (spec wins over brief's "below/inside"),
            // numbered for duplicates (displayNames): "Maze (1)", "Maze (2)"…
            val displayName = names[room.id] ?: room.name
            if (displayName.isNotBlank()) {
                val layout = textMeasurer.measure(text = displayName, style = TextStyle(fontSize = nameFont))
                drawText(
                    layout,
                    color = roomNameColor,
                    alpha = anim.pop,
                    topLeft = Offset(
                        c.x - layout.size.width / 2f,
                        rect.bottom + nameFont.value * density * 0.5f,
                    ),
                )
            }
        }
    }
}

/** Nearest-room hit test: a room wins if its center is within GRID_STEP/2 of the tap (world space). */
private fun roomAt(map: MapFile?, camera: CameraState, screen: Offset): String? {
    val rooms = map?.rooms ?: return null
    val world = camera.screenToWorld(screen)
    val maxSq = (GRID_STEP / 2f) * (GRID_STEP / 2f)
    var bestId: String? = null
    var bestSq = maxSq
    for (room in rooms) {
        val dx = world.x - room.x
        val dy = world.y - room.y
        val d2 = dx * dx + dy * dy
        if (d2 <= bestSq) {
            bestSq = d2
            bestId = room.id
        }
    }
    return bestId
}

/**
 * Nearest-exit hit test for long-press (screen space, ~16px finger tolerance):
 * returns the id of the closest exit line within tolerance. For a two-way
 * passage both records overlap the same anchored segment and either id edits
 * the same passage (the one-way toggle and delete are direction-symmetric).
 */
private fun exitAt(map: MapFile?, camera: CameraState, screen: Offset): String? {
    if (map == null) return null
    var bestId: String? = null
    var bestDist = 16f
    for (exit in map.exits) {
        // A mirror pair shares one drawn polyline (the canonical record's
        // route); hit-test that. Either id still edits the same passage
        // (the one-way toggle and delete are direction-symmetric).
        val source = if (exit.from > exit.to) {
            map.exits.firstOrNull {
                it.from == exit.to && it.to == exit.from &&
                    it.direction == exit.direction.opposite()
            } ?: exit
        } else exit
        // Settled positions are fine: the route shape (and hence the polyline) is the
        // same the user just looked at, and the 16px tolerance absorbs glide drift.
        // Self-exits hit-test their loop stalk; containment self-exits route to
        // null and are skipped (they don't draw).
        val route = routeExit(source, map) ?: continue
        val pts = route.polyline()
        if (pts.size < 2) continue
        var d = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = camera.worldToScreen(pts[i])
            val b = camera.worldToScreen(pts[i + 1])
            val di = pointSegDist(screen, a, b)
            if (di < d) d = di
        }
        if (d < bestDist) {
            bestDist = d
            bestId = exit.id
        }
    }
    return bestId
}

/**
 * Tapped stub hit test (screen space): returns the target room id of the
 * stub line (or its "→ name" label) nearest the tap, within ~16px. Stubs are
 * tappable so the camera can hop to the named target — the ZUG
 * "(to Troll Room)" jump. Label extents are estimated from the same width
 * model the router uses (LABEL_CHAR_W * LABEL_FONT_WORLD per character);
 * good enough for a finger tolerance.
 */
private fun stubTargetAt(map: MapFile?, camera: CameraState, screen: Offset): String? {
    val m = map ?: return null
    var bestId: String? = null
    var bestDist = 16f
    for (exit in m.exits) {
        // Same owner rule as drawing: one stub per passage pair.
        val source = if (exit.from > exit.to) {
            m.exits.firstOrNull {
                it.from == exit.to && it.to == exit.from &&
                    it.direction == exit.direction.opposite()
            } ?: exit
        } else exit
        val route = routeExit(source, m) as? ExitRoute.Stub ?: continue
        val from = camera.worldToScreen(route.from)
        val tip = camera.worldToScreen(route.tip)
        var d = pointSegDist(screen, from, tip)
        // Label center mirrors the draw math: pushed out along the dominant axis.
        val labelW = (route.targetName.length + 2f) * LABEL_CHAR_W * LABEL_FONT_WORLD * camera.scale
        val labelH = LABEL_FONT_WORLD * camera.scale
        val u = unitBearing(route.direction)
        val labelCenter = if (kotlin.math.abs(u.x) > kotlin.math.abs(u.y)) {
            Offset(tip.x + (if (u.x > 0f) 1f else -1f) * (4f + labelW / 2f), tip.y)
        } else {
            Offset(tip.x, tip.y + (if (u.y > 0f) 1f else -1f) * (labelH / 2f))
        }
        d = minOf(d, kotlin.math.hypot(screen.x - labelCenter.x, screen.y - labelCenter.y) - maxOf(labelW, labelH) / 2f)
        if (d < bestDist) {
            bestDist = d
            bestId = route.targetId
        }
    }
    return bestId
}

private fun pointSegDist(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    if (l2 < 1e-6f) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
    return kotlin.math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
