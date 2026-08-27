package com.jrod.droidgridder.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.jrod.droidgridder.model.GRID_STEP
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Pos

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

// Room box in world units (~GRID_STEP * 0.7 per the task brief).
private val ROOM_BOX_SIZE = GRID_STEP * 0.7f
private const val ROOM_BOX_RADIUS = 12f

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
    onTapEmpty: () -> Unit,
) {
    val map = state.map
    val background = MaterialTheme.colorScheme.background
    val roomFill = MaterialTheme.colorScheme.surface
    val boxStroke = MaterialTheme.colorScheme.outline
    val currentColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.secondary
    val exitColor = MaterialTheme.colorScheme.outline
    val exitLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val roomNameColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        roomAt(map, camera, pos)?.let(onDoubleTapRoom)
                    },
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
        val exitFont = ((11f * camera.scale).coerceIn(7f, 28f)).sp

        // Exits first: center-to-center lines with the direction label near the
        // midpoint, nudged toward the target side so opposite edges don't overlap.
        for (exit in map.exits) {
            val from = roomsById[exit.from] ?: continue
            val to = roomsById[exit.to] ?: continue
            if (from.id == to.id) continue
            val s = camera.worldToScreen(Pos(from.x, from.y))
            val t = camera.worldToScreen(Pos(to.x, to.y))
            drawLine(color = exitColor, start = s, end = t, strokeWidth = 2f)
            val mid = Offset((s.x + t.x) / 2f, (s.y + t.y) / 2f)
            drawCenteredLabel(
                textMeasurer = textMeasurer,
                text = exit.direction.name,
                center = Offset(mid.x + (t.x - s.x) * 0.18f, mid.y + (t.y - s.y) * 0.18f),
                fontSize = exitFont,
                color = exitLabelColor,
                bg = roomFill,
            )
        }

        val boxSize = ROOM_BOX_SIZE * camera.scale
        val half = boxSize / 2f
        val radius = CornerRadius(ROOM_BOX_RADIUS * camera.scale)
        val nameFont = ((13f * camera.scale).coerceIn(9f, 32f)).sp

        for (room in map.rooms) {
            val c = camera.worldToScreen(Pos(room.x, room.y))
            val rect = Rect(c.x - half, c.y - half, c.x + half, c.y + half)
            drawRoundRect(
                color = roomFill, topLeft = rect.topLeft, size = rect.size, cornerRadius = radius,
            )
            drawRoundRect(
                color = boxStroke, topLeft = rect.topLeft, size = rect.size,
                cornerRadius = radius, style = Stroke(width = 2f),
            )
            if (room.id == state.currentRoomId) {
                drawRoundRect(
                    color = currentColor, topLeft = rect.topLeft, size = rect.size,
                    cornerRadius = radius, style = Stroke(width = 3f),
                )
            }
            if (room.id == state.selectedRoomId) {
                val ring = rect.inflate(8f)
                drawRoundRect(
                    color = selectedColor, topLeft = ring.topLeft, size = ring.size,
                    cornerRadius = CornerRadius(ROOM_BOX_RADIUS * camera.scale + 8f),
                    style = Stroke(width = 3f),
                )
            }
            // Room name is drawn under the box (spec wins over brief's "below/inside").
            if (room.name.isNotBlank()) {
                val layout = textMeasurer.measure(text = room.name, style = TextStyle(fontSize = nameFont))
                drawText(
                    layout,
                    color = roomNameColor,
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

private fun DrawScope.drawCenteredLabel(
    textMeasurer: TextMeasurer,
    text: String,
    center: Offset,
    fontSize: TextUnit,
    color: Color,
    bg: Color,
) {
    val layout = textMeasurer.measure(text = text, style = TextStyle(fontSize = fontSize))
    // Filled pill keeps the direction label readable over room names and exit lines.
    val hPad = layout.size.height * 0.45f
    val vPad = layout.size.height * 0.25f
    val pill = Rect(
        left = center.x - layout.size.width / 2f - hPad,
        top = center.y - layout.size.height / 2f - vPad,
        right = center.x + layout.size.width / 2f + hPad,
        bottom = center.y + layout.size.height / 2f + vPad,
    )
    drawRoundRect(color = bg, topLeft = pill.topLeft, size = pill.size, cornerRadius = CornerRadius(pill.height / 2f))
    drawText(
        layout,
        color = color,
        topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f),
    )
}
