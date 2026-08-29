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
import com.jrod.droidgridder.model.directionOffset
import com.jrod.droidgridder.model.hasMirror
import com.jrod.droidgridder.model.opposite
import com.jrod.droidgridder.model.routeExit

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

/** The route's world points as a drawable polyline (Stub = from..tip). */
private fun ExitRoute.polyline(): List<Pos> = when (this) {
    is ExitRoute.Straight -> listOf(from, to)
    is ExitRoute.Bends -> points
    is ExitRoute.Stub -> listOf(from, tip)
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
                        val room = roomAt(map, camera, pos)
                        if (room != null) onTapRoom(room) else onTapEmpty()
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

        val roomsById = map.rooms.associateBy { it.id }

        // v1.5 ruling O1: pure world scaling, no clamp — labels keep constant world width at
        // every zoom (overlap-free by layout stride), pinch-in makes them larger.
        val nameFont = (13f * camera.scale).sp
        val radius = CornerRadius(ROOM_BOX_RADIUS * camera.scale)

        // v1.6.1 routed connectors: route every exit around the OTHER rooms'
        // footprints (Manhattan gutters; labeled stub when walled in). Routes are
        // pure functions of the current positions, so they track the animated
        // glides. A mirror pair shares one route — same endpoints, reversed.
        val routedMap = map.copy(rooms = map.rooms.map { r ->
            animatedRooms[r.id]?.let { r.copy(x = it.x, y = it.y) } ?: r
        })
        val routes = HashMap<String, ExitRoute?>()
        for (exit in map.exits) {
            if (exit.from == exit.to) continue
            val k = listOf(exit.from, exit.to).sorted().joinToString("|")
            routes[k] = routes[k] ?: routeExit(exit, routedMap)
        }

        // Exits first (under the boxes): polylines run from the source box's
        // direction anchor to the destination box's opposite-direction anchor,
        // bent around obstructions. A two-way mirror computes the identical
        // polyline, so mirrors still draw as one line.
        // ponytail: no connector draw-in stroke (Task 7 optional) — per-exit animation
        // state for little visual gain.
        for (exit in map.exits) {
            val from = roomsById[exit.from] ?: continue
            val to = roomsById[exit.to] ?: continue
            if (from.id == to.id) continue
            val route = routes[listOf(exit.from, exit.to).sorted().joinToString("|")] ?: continue
            val pts = route.polyline().map { camera.worldToScreen(it) }
            // v1.6 ruling Q1 (replaces v1.4 N2's always-blue vertical clause): one rule for
            // every edge — touching the selected room is secondary (green) 3dp; the rest stay
            // outline (grey) 2dp. UP/DOWN follow the same selection rule as all other edges.
            val isSelectedEdge = exit.from == state.selectedRoomId || exit.to == state.selectedRoomId
            val lineColor = if (isSelectedEdge) selectedColor else exitColor
            val path = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            }
            drawPath(path, color = lineColor, style = Stroke(width = if (isSelectedEdge) 3f else 2f))
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
            // Walled-in edge: labeled stub naming the destination, pushed out along
            // the bearing's dominant axis so the text doesn't sit on the line.
            if (route is ExitRoute.Stub) {
                val layout = textMeasurer.measure(text = "→ ${route.targetName}", style = TextStyle(fontSize = nameFont))
                val off = directionOffset(exit.direction)
                val tip = camera.worldToScreen(route.tip)
                val cx = if (kotlin.math.abs(off.x) > kotlin.math.abs(off.y)) {
                    tip.x + (if (off.x > 0f) 1f else -1f) * (4f + layout.size.width / 2f)
                } else {
                    tip.x
                }
                val cy = if (kotlin.math.abs(off.x) > kotlin.math.abs(off.y)) {
                    tip.y
                } else {
                    tip.y + (if (off.y > 0f) 1f else -1f) * (layout.size.height / 2f)
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
            drawRoundRect(
                color = roomFill, topLeft = rect.topLeft, size = rect.size, cornerRadius = radius,
            )
            drawRoundRect(
                color = boxStroke, topLeft = rect.topLeft, size = rect.size,
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
            // Room name is drawn under the box (spec wins over brief's "below/inside").
            if (room.name.isNotBlank()) {
                val layout = textMeasurer.measure(text = room.name, style = TextStyle(fontSize = nameFont))
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
        if (exit.from == exit.to) continue
        // Settled positions are fine: the route shape (and hence the polyline) is the
        // same the user just looked at, and the 16px tolerance absorbs glide drift.
        val route = routeExit(exit, map) ?: continue
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

private fun pointSegDist(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    if (l2 < 1e-6f) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
    return kotlin.math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
