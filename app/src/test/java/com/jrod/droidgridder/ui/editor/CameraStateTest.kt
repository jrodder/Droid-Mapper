package com.jrod.droidgridder.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.jrod.droidgridder.model.Direction
import com.jrod.droidgridder.model.Pos
import com.jrod.droidgridder.model.ROOM_BOX_SIZE
import com.jrod.droidgridder.model.Room
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CameraState is pure math (plus snapshot state), so the world<->screen transform,
 * pan, and clamped pivot-anchored zoom are covered here instead of on-device pinches.
 */
class CameraStateTest {
    // v1.6 pin rule: an exit line meets the box at the compass-true point — cardinals
    // at edge midpoints, diagonals at the exact corner, UP/DOWN at the top/bottom
    // midpoint, IN/OUT at the center (containment has no compass).
    @Test
    fun `exitAnchor pins lines to corners for diagonals and midpoints for cardinals`() {
        fun expected(cx: Float, cy: Float, d: Direction): Offset {
            val hx = ROOM_BOX_SIZE / 2f
            return when (d) {
                Direction.N -> Offset(cx, cy - hx)
                Direction.S -> Offset(cx, cy + hx)
                Direction.E -> Offset(cx + hx, cy)
                Direction.W -> Offset(cx - hx, cy)
                Direction.NE -> Offset(cx + hx, cy - hx)
                Direction.NW -> Offset(cx - hx, cy - hx)
                Direction.SE -> Offset(cx + hx, cy + hx)
                Direction.SW -> Offset(cx - hx, cy + hx)
                Direction.UP -> Offset(cx, cy - hx)
                Direction.DOWN -> Offset(cx, cy + hx)
                Direction.IN, Direction.OUT -> Offset(cx, cy)
            }
        }
        val r = Room(id = "r", x = 100f, y = 200f)
        for (d in Direction.entries) {
            assertEquals("$d", expected(100f, 200f, d), exitAnchor(r, d))
        }
    }


    @Test
    fun identityTransformRoundTrip() {
        val cam = CameraState()
        val w = Pos(100f, -250f)
        val s = cam.worldToScreen(w)
        assertEquals(100f, s.x, 0.001f)
        assertEquals(-250f, s.y, 0.001f)
        assertEquals(w, cam.screenToWorld(s))
    }

    @Test
    fun panShiftsOffsetAndRoundTrips() {
        val cam = CameraState()
        cam.panBy(Offset(50f, -30f))
        val s = cam.worldToScreen(Pos(0f, 0f))
        assertEquals(50f, s.x, 0.001f)
        assertEquals(-30f, s.y, 0.001f)
        assertEquals(Pos(0f, 0f), cam.screenToWorld(s))
    }

    @Test
    fun zoomScaleIsClamped() {
        val cam = CameraState()
        val pivot = Offset(0f, 0f)
        cam.zoomBy(8f, pivot)
        assertEquals(CameraState.MAX_SCALE, cam.scale, 0.0001f)
        cam.zoomBy(1f / 64f, pivot)
        assertEquals(CameraState.MIN_SCALE, cam.scale, 0.0001f)
    }

    @Test
    fun zoomKeepsPivotStationary() {
        val cam = CameraState()
        val pivot = Offset(300f, 700f)
        val world = cam.screenToWorld(pivot)
        cam.zoomBy(2f, pivot)
        val after = cam.worldToScreen(world)
        assertEquals(2f, cam.scale, 0.0001f)
        assertEquals(pivot.x, after.x, 0.01f)
        assertEquals(pivot.y, after.y, 0.01f)
    }

    @Test
    fun centerOnPutsWorldAtCanvasCenter() {
        val cam = CameraState()
        cam.centerOn(Pos(10f, 20f), Size(1000f, 800f))
        val s = cam.worldToScreen(Pos(10f, 20f))
        assertEquals(500f, s.x, 0.001f)
        assertEquals(400f, s.y, 0.001f)
        assertEquals(1f, cam.scale, 0.0001f)
    }
}