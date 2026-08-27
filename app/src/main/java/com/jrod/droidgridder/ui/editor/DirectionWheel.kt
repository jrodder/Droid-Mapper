package com.jrod.droidgridder.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jrod.droidgridder.model.Direction

private val WHEEL_SIZE = 240.dp
private val BUTTON_SIZE = 44.dp
private val CARDINAL_PAD = 4.dp
private val DIAGONAL_PAD = 28.dp
private val CENTER_SHIFT = 24.dp

/**
 * Direction wheel overlay: eight compass buttons in a ring plus UP/DOWN/IN/OUT
 * in a 2×2 cluster at the center, centered on [center] (screen px of the wheel
 * room). A tap on a button fires [onDirection]; a long-press fires
 * [onLongPressDirection] (link mode). Only the buttons consume input, so
 * canvas gestures pass through everywhere else.
 */
@Composable
fun DirectionWheel(
    center: Offset,
    onDirection: (Direction) -> Unit,
    onLongPressDirection: (Direction) -> Unit,
) {
    val (dx, dy) = with(LocalDensity.current) {
        val half = WHEEL_SIZE.toPx() / 2f
        (center.x - half).toDp() to (center.y - half).toDp()
    }
    Box(modifier = Modifier.offset(x = dx, y = dy).size(WHEEL_SIZE)) {
        wheelButton(Direction.N, Modifier.align(Alignment.TopCenter).padding(top = CARDINAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.S, Modifier.align(Alignment.BottomCenter).padding(bottom = CARDINAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.E, Modifier.align(Alignment.CenterEnd).padding(end = CARDINAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.W, Modifier.align(Alignment.CenterStart).padding(start = CARDINAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.NE, Modifier.align(Alignment.TopEnd).padding(top = DIAGONAL_PAD, end = DIAGONAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.NW, Modifier.align(Alignment.TopStart).padding(top = DIAGONAL_PAD, start = DIAGONAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.SE, Modifier.align(Alignment.BottomEnd).padding(bottom = DIAGONAL_PAD, end = DIAGONAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.SW, Modifier.align(Alignment.BottomStart).padding(bottom = DIAGONAL_PAD, start = DIAGONAL_PAD), onDirection, onLongPressDirection)
        wheelButton(Direction.UP, Modifier.align(Alignment.Center).offset(x = -CENTER_SHIFT, y = -CENTER_SHIFT), onDirection, onLongPressDirection)
        wheelButton(Direction.DOWN, Modifier.align(Alignment.Center).offset(x = -CENTER_SHIFT, y = CENTER_SHIFT), onDirection, onLongPressDirection)
        wheelButton(Direction.IN, Modifier.align(Alignment.Center).offset(x = CENTER_SHIFT, y = -CENTER_SHIFT), onDirection, onLongPressDirection)
        wheelButton(Direction.OUT, Modifier.align(Alignment.Center).offset(x = CENTER_SHIFT, y = CENTER_SHIFT), onDirection, onLongPressDirection)
    }
}

@Composable
private fun wheelButton(
    direction: Direction,
    modifier: Modifier,
    onDirection: (Direction) -> Unit,
    onLongPressDirection: (Direction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(BUTTON_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape)
            .semantics { contentDescription = direction.name }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onDirection(direction) },
                    onLongPress = { onLongPressDirection(direction) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(direction.name, style = MaterialTheme.typography.labelSmall)
    }
}
