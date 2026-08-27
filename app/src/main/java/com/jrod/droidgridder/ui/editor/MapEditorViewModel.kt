package com.jrod.droidgridder.ui.editor

import androidx.lifecycle.ViewModel
import com.jrod.droidgridder.data.MapStore
import com.jrod.droidgridder.model.MapFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MapEditorUiState(
    val map: MapFile? = null,
    val currentRoomId: String? = null,
    val selectedRoomId: String? = null,
    val wheelForRoomId: String? = null,
)

/**
 * Editor state machine. Task 4 adds the skeleton plus selection/wheel actions;
 * the map-mutating actions (go/link/delete/update, ...) land in Tasks 5-6.
 */
class MapEditorViewModel(mapId: String, store: MapStore) : ViewModel() {
    private val _uiState = MutableStateFlow(MapEditorUiState())
    val uiState: StateFlow<MapEditorUiState> = _uiState.asStateFlow()

    init {
        val map = store.load(mapId)
        _uiState.value = MapEditorUiState(
            map = map,
            currentRoomId = map?.rooms?.firstOrNull()?.id,
        )
    }

    /** Select a room (also makes it current); `null` clears the selection. */
    fun select(roomId: String?) {
        _uiState.update {
            it.copy(selectedRoomId = roomId, currentRoomId = roomId ?: it.currentRoomId)
        }
    }

    fun openWheel(roomId: String) {
        _uiState.update { it.copy(wheelForRoomId = roomId, currentRoomId = roomId) }
    }

    fun closeWheel() {
        _uiState.update { it.copy(wheelForRoomId = null) }
    }
}