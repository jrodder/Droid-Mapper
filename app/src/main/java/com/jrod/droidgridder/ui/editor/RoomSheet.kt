package com.jrod.droidgridder.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jrod.droidgridder.model.MapFile
import com.jrod.droidgridder.model.Room

/**
 * Bottom sheet for the selected room: editable name/description/notes (committed
 * on focus loss or IME action, one commit per editing session) plus per-exit
 * delete/redirect actions and a delete-room confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSheet(
    room: Room,
    map: MapFile,
    onCommitText: (name: String, description: String, notes: String) -> Unit,
    onDeleteRoom: () -> Unit,
    onDeleteExit: (exitId: String) -> Unit,
    onRedirectExit: (exitId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var sheetShown by remember { mutableStateOf(true) }
    if (!sheetShown) return
    var name by remember { mutableStateOf(room.name) }
    var description by remember { mutableStateOf(room.description) }
    var notes by remember { mutableStateOf(room.notes) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exits = map.exits.filter { it.from == room.id }

    // Disposal (scrim tap, swipe-down, back) is a final focus loss that Compose does not
    // report via onFocusChanged — commit a still-dirty draft so it is not silently lost.
    // The dirty check keeps an already-committed draft from committing twice.
    DisposableEffect(room.id) {
        onDispose {
            if (name != room.name || description != room.description || notes != room.notes) {
                onCommitText(name, description, notes)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { sheetShown = false; onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            DraftField("Name", name, { name = it }, { onCommitText(name, description, notes) })
            DraftField("Description", description, { description = it }, { onCommitText(name, description, notes) })
            DraftField("Notes", notes, { notes = it }, { onCommitText(name, description, notes) })

            Text(
                text = "Exits",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            if (exits.isEmpty()) {
                Text(text = "No exits", style = MaterialTheme.typography.bodyMedium)
            }
            exits.forEach { exit ->
                val dest = map.rooms.firstOrNull { it.id == exit.to }?.name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${exit.direction.name} → ${dest?.takeIf { it.isNotBlank() } ?: "(unmapped)"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRedirectExit(exit.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Redirect ${exit.direction.name} exit")
                    }
                    IconButton(onClick = { onDeleteExit(exit.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete ${exit.direction.name} exit")
                    }
                }
            }

            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text("Delete room")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete room?") },
            text = { Text("Removes this room and every exit touching it. You can undo from the top bar.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteRoom() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** Single-line draft field that commits on focus loss or the IME done action. */
@Composable
private fun DraftField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { fs ->
                if (focused && !fs.isFocused) onCommit()
                focused = fs.isFocused
            },
    )
}
