package com.jrod.droidgridder.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.Pos

/**
 * Full-screen editor for one map. Hosted in the [com.jrod.droidgridder.ui.navigation.AppNav]
 * editor route; the top-bar back arrow pops back to the list (AppNav owns the
 * NavController, so [onBack] is a callback — navigation 2.8 has no LocalNavController).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapEditorScreen(store: MapStore, mapId: String, onBack: () -> Unit) {
    val viewModel: MapEditorViewModel = viewModel(
        key = mapId,
        factory = viewModelFactory { initializer { MapEditorViewModel(mapId, store) } },
    )
    val state by viewModel.uiState.collectAsState()
    val camera = remember { CameraState() }
    val centered = remember { mutableStateOf(false) }

    // ponytail: text "Undo"/"Tidy" actions — no glyphs for either in material-icons-core, and
    // the icons-extended artifact is not worth pulling in for two text buttons (Task 8+ may revisit).
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(state.map?.name?.ifBlank { null } ?: "Droid-Gridder") },
                actions = {
                    IconButton(onClick = { viewModel.autoTidy() }) {
                        Text("Tidy", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick = { viewModel.undo() }, enabled = state.canUndo) {
                        Text("Undo", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { intSize ->
                    val size = intSize.toSize()
                    // Center the camera on the average room position once the canvas size is known.
                    if (!centered.value && size != Size.Zero) {
                        val rooms = state.map?.rooms.orEmpty()
                        if (rooms.isNotEmpty()) {
                            val avg = Pos(rooms.map { it.x }.sum() / rooms.size, rooms.map { it.y }.sum() / rooms.size)
                            camera.centerOn(avg, size)
                            centered.value = true
                        }
                    }
                },
        ) {
            MapCanvas(
                state = state,
                camera = camera,
                onTapRoom = { id ->
                    when {
                        state.redirectMode != null -> viewModel.completeRedirect(id)
                        state.linkMode != null -> viewModel.completeLink(id)
                        else -> viewModel.select(id)
                    }
                },
                onDoubleTapRoom = { id ->
                    // In link/redirect mode a double-tap completes the pending mode instead of opening the edit sheet.
                    when {
                        state.redirectMode != null -> viewModel.completeRedirect(id)
                        state.linkMode != null -> viewModel.completeLink(id)
                        else -> viewModel.openRoomEdit(id)
                    }
                },
                onTapEmpty = {
                    when {
                        state.redirectMode != null -> viewModel.completeRedirect(null)
                        state.linkMode != null -> viewModel.completeLink(null)
                        else -> viewModel.select(null)
                    }
                },
            )
            state.map?.rooms?.firstOrNull { it.id == state.wheelForRoomId }?.let { wheelRoom ->
                DirectionWheel(
                    center = camera.worldToScreen(Pos(wheelRoom.x, wheelRoom.y)),
                    onDirection = viewModel::go,
                    onLongPressDirection = viewModel::startLink,
                )
            }
            state.linkMode?.let { direction ->
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "Link ${direction.name}: tap a room",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            state.redirectMode?.let { r ->
                val fromName = state.map?.rooms?.firstOrNull { it.id == r.fromRoomId }?.name.orEmpty()
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "Redirect ${r.direction.name} from $fromName: tap a room",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            // Room windows (v1.1): the edit sheet and the read-only detail window are
            // mutually exclusive via roomMode; the detail window sits above the sheet in
            // composition order per the interaction ruling.
            val selectedRoom = state.map?.rooms?.firstOrNull { it.id == state.selectedRoomId }
            if (state.roomMode == RoomMode.Edit && selectedRoom != null) {
                key(selectedRoom.id) {
                    RoomSheet(
                        room = selectedRoom,
                        map = state.map!!,
                        onCommitText = { n, d, no -> viewModel.updateRoomText(selectedRoom.id, n, d, no) },
                        onManageExits = { viewModel.openWheelFromEdit() },
                        onDeleteRoom = { viewModel.deleteRoom(selectedRoom.id) },
                        onDeleteExit = viewModel::deleteExit,
                        onRedirectExit = viewModel::startRedirect,
                        onDismiss = { viewModel.select(null) },
                    )
                }
            }
            if (state.roomMode == RoomMode.Detail && selectedRoom != null) {
                RoomDetailWindow(
                    room = selectedRoom,
                    onClose = { viewModel.closeDetail() },
                )
            }
        }
    }
}
