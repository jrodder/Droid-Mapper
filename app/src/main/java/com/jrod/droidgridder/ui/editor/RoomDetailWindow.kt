package com.jrod.droidgridder.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jrod.droidgridder.model.Room

/**
 * Full-screen read-only detail window for the selected room (v1.1 detail mode).
 * Shows the name (or "(unnamed)"), description and notes (blank sections show
 * nothing). v1.3 ruling M: the exits list is gone — the canvas highlights the
 * selected room's connected exit lines instead. There are no buttons or actions:
 * the entire window is one tap target that [onClose] (closes the window, keeps
 * the selection). The clickable Box consumes the tap, so it never passes through
 * to the canvas.
 */
@Composable
fun RoomDetailWindow(
    room: Room,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClose),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = room.name.ifBlank { "(unnamed)" },
                style = MaterialTheme.typography.headlineMedium,
            )
            room.description.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = "Description",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
            room.notes.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Text(text = notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
